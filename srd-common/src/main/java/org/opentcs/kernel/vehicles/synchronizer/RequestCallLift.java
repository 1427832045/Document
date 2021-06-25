package org.opentcs.kernel.vehicles.synchronizer;

import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;

import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.RequestBefore;
import org.opentcs.drivers.vehicle.synchronizer.RequestWhile;
import org.apache.http.client.fluent.Request;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestCallLift implements RequestBefore, RequestWhile {

    private static final Logger LOG = LoggerFactory.getLogger(RequestCallLift.class);
    private static final String callLift = "device:callLift";
    private static final String occupyLift = "device:occupyLift";

    private final String urlBase;

    @Inject
    public RequestCallLift() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getLiftRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {

        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(callLift) ||
                props.containsKey(occupyLift)) {

            final String liftDescription;
            if (props.containsKey(callLift)) {
                liftDescription = props.getOrDefault(callLift, "Unknown:null");
            } else {
                liftDescription = props.getOrDefault(occupyLift, "Unknown:null");
            }

            try {
                String[] liftDescriptionArray = liftDescription.split(":");
                String liftName = liftDescriptionArray[0];
                String liftFloor = liftDescriptionArray[1];
                String url = props.getOrDefault(SPECIFIED_SERVER_ROUTE, urlBase) +
                        "lifts/" +
                        liftName +
                        "?action=call&floor=" +
                        liftFloor;
                HttpResponse response = Request.Post(url).connectTimeout(5000)
                        .socketTimeout(5000)
                        .execute()
                        .returnResponse();
                int retCode = response.getStatusLine().getStatusCode();
                if (200 == retCode) {
                    ret = new ExplainedBoolean(true, "Call/Occupy lift request OK.");
                } else {
                    ret = new ExplainedBoolean(false, String.format("Call/Occupy lift failed: %d", retCode));
                }
            } catch (IOException ex) {
                LOG.info("Exception in call/occupy lift: " + ex.getMessage());
                ret = new ExplainedBoolean(false, "Call/Occupy lift exception:" + ex.getMessage());
            } catch (ArrayIndexOutOfBoundsException ex) {
                LOG.info("Exception in call/occupy lift's properties: " + ex.getMessage());
                ret = new ExplainedBoolean(false, "Call/Occupy lift's exception: " + ex.getMessage());
            }
        }

        return ret;
    }
}