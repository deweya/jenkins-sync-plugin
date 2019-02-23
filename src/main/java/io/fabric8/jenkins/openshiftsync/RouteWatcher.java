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
    private String trackedRoute;
    
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
									+ namespace + " and resource version"
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
	
	private void onInitialRoutes(RouteList routes) {
		if (routes == null)
			return;
		List<Route> items = routes.getItems();
		if (items != null) {
			for (Route route : items) {
				try {
					logger.log(Level.INFO, "Found a valid route!!");
				} catch (Exception e) {
					logger.log(SEVERE, "Failed to update job", e);
				}
			}
		}
	}
	
	@Override
	public <T> void eventReceived(Action action, T resource) {
		Route route = (Route) resource;
		eventReceived(action, route);
	}
}
