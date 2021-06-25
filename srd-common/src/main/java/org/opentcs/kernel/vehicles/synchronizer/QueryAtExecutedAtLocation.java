package org.opentcs.kernel.vehicles.synchronizer;


import javax.annotation.Nonnull;

import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.QueryAtExecuted;

public class QueryAtExecutedAtLocation implements QueryAtExecuted {

    private static final String queryKey = "device:queryAtExecuted";
    private final String urlBase;

    @Inject
    public QueryAtExecutedAtLocation() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getLocationRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {
        return CommonLocationQuery.request(vehicle,
                command,
                queryKey,
                SPECIFIED_SERVER_ROUTE,
                urlBase);
    }
}