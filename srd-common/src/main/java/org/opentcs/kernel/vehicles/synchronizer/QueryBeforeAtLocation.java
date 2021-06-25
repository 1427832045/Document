package org.opentcs.kernel.vehicles.synchronizer;

import javax.annotation.Nonnull;

import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.QueryBefore;

public class QueryBeforeAtLocation implements QueryBefore {

    private static final String queryKey = "device:queryBefore";
    private final String urlBase;

    @Inject
    public QueryBeforeAtLocation() {
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