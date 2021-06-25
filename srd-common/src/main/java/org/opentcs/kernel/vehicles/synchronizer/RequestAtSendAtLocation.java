package org.opentcs.kernel.vehicles.synchronizer;

import javax.annotation.Nonnull;

import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.RequestAtSend;

public class RequestAtSendAtLocation implements RequestAtSend {

    private static final String requestKey = "device:requestAtSend";
    private final String urlBase;

    @Inject
    public RequestAtSendAtLocation() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getLocationRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {
        return CommonLocationRequest.request(vehicle, command, requestKey, SPECIFIED_SERVER_ROUTE, urlBase);
    }
}