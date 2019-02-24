package io.fabric8.jenkins.openshiftsync;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.SEVERE;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;

public class RouteWatcher extends BaseWatcher {
    private Route trackedRoute;
    
    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public RouteWatcher(String[] namespaces) {
        super(namespaces);
    }
    
	@Override
	public int getListIntervalInSeconds() {
		return GlobalPluginConfiguration.get().getRouteListInterval();
	}
	
	@Override
	public Runnable getStartTimerTask() {
		return new SafeTimerTask() {
			@Override
			public void doRun() throws Exception {
                if (!CredentialsUtils.hasCredentials()) {
                    logger.fine("No Openshift Token credential defined.");
                    return;
                }
				for (String namespace : namespaces) {
					RouteList routes = null;
					try {
                        logger.fine("listing Routes resources");
                        routes = getAuthenticatedOpenShiftClient().routes()
                                .inNamespace(namespace)
                                // TODO: Don't hardcode this - add this to Constants
                                .withLabel("route.sync.jenkins.openshift.io", Constants.VALUE_SECRET_SYNC).list();
                        onInitialRoutes(routes);
                        logger.fine("handled Routes resources");
					} catch (Exception e) {
                        logger.log(SEVERE, "Failed to load Routes: " + e, e);
					}
					try {
						String resourceVersion = "0";
						if (routes == null) {
							logger.warning("Unable to get route list; impacts version used for watch");
						} else {
							resourceVersion = routes.getMetadata().getResourceVersion();
						}
						if (watches.get(namespace) == null) {
							logger.info("creating Route watch for namespace "
									+ namespace + " and resource version "
									+ resourceVersion);
							addWatch(namespace,
									getAuthenticatedOpenShiftClient()
									.routes()
									.inNamespace(namespace)
									// TODO: Again, add this to Constants once we decide on a good label
									.withLabel("route.sync.jenkins.openshift.io",
											Constants.VALUE_SECRET_SYNC)
											.withResourceVersion(
													resourceVersion)
													.watch(new WatcherCallback<Route>(RouteWatcher.this,
															namespace)));
						}
					} catch (Exception e) {
						logger.log(SEVERE, "Failed to load Routes: " + e, e);
					}
				}
			}
			
		};
	}
	
	public void start() {
		super.start();
		logger.info("Now handling startup routes!!");
	}
	
	private String getRouteUrl(Route route) {
		String url = "";
		if (route.getSpec().getTls() == null) {
			url += "http://";
		} else {
			url += "https://";
		}
		url += route.getSpec().getHost();
		return url;
	}
	
	// Needed in case multiple Jenkins instances are colocated in the same namespace
	// We don't want to watch a route that is already being watched by another Jenkins instance
	private boolean hasWatchedAnnotation(Route route) {
		String value = route.getMetadata().getAnnotations().get("sync.jenkins.openshift.io/watched");
		if (value != null && value.equals(Constants.VALUE_SECRET_SYNC)) {
			return true;
		}
		return false;
	}
	
	private void addWatchedAnnotation(Route route) {
		getAuthenticatedOpenShiftClient().routes().withName(route.getMetadata().getName()).edit()
											.editMetadata()
											.addToAnnotations("sync.jenkins.openshift.io/watched", Constants.VALUE_SECRET_SYNC)
											.endMetadata()
											.done();
	}
	
	private void onInitialRoutes(RouteList routes) {
		if (routes == null)
			return;
		List<Route> items = routes.getItems();
		if (items != null) {
			for (Route route : items) {
				try {
					if (trackedRoute != null)
						return;
					if (hasWatchedAnnotation(route))
						continue;
					trackedRoute = route;
					logger.log(Level.INFO, "Found a valid route!!");
					addWatchedAnnotation(route);
					JenkinsUtils.setRootUrl(getRouteUrl(route));
				} catch (Exception e) {
					logger.log(SEVERE, "Failed to update Route", e);
				}
			}
		}
	}
	
	public void eventReceived(Action action, Route route) {
		try {
			switch (action) {
			case ADDED:
				if (trackedRoute != null)
					break;
				trackedRoute = route;
				addWatchedAnnotation(route);
				JenkinsUtils.setRootUrl(getRouteUrl(route));
				break;
			case MODIFIED:
				if (trackedRoute != route)
					break;
				// We don't want users to remove the watched annotation from the route
				if (!hasWatchedAnnotation(route)) {
					addWatchedAnnotation(route);
				}
				JenkinsUtils.setRootUrl(getRouteUrl(route));
				break;
			case DELETED:
				if (trackedRoute != route)
					break;
				trackedRoute = null;
				JenkinsUtils.setRootUrl(null);
				break;
			case ERROR:
				logger.warning("watch for route " + route.getMetadata().getName() + " received error event ");
				break;
			default:
				logger.warning("watch for route " + route.getMetadata().getName() + " received unknown event " + action);
				break;
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "Caught: " + e, e);
		}
	}
	
	@Override
	public <T> void eventReceived(Action action, T resource) {
		Route route = (Route) resource;
		eventReceived(action, route);
	}
}
