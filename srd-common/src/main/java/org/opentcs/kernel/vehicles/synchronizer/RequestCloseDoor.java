package org.opentcs.kernel.vehicles.synchronizer;

import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;

import javax.inject.Inject;

import com.seer.srd.route.RouteConfigKt;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.RequestAfterExecuted;
import org.apache.http.client.fluent.Request;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestCloseDoor implements RequestAfterExecuted {

    private static final Logger LOG = LoggerFactory.getLogger(RequestCloseDoor.class);
    private static final String passDoor = "device:passDoor";

    private final String urlBase;

    @Inject
    public RequestCloseDoor() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getDoorRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {

        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(passDoor)) {
            String[] doors = props.getOrDefault(passDoor, "UnknowDoor").split(",");
            final String actualUrl = props.getOrDefault(SPECIFIED_SERVER_ROUTE, urlBase);
            for (String door : doors) {
                String url = actualUrl + "doors/" + door + "?action=close";
                try {
                    HttpResponse response = Request.Post(url).connectTimeout(5000)
                            .socketTimeout(5000)
                            .execute()
                            .returnResponse();
                    int retCode = response.getStatusLine().getStatusCode();
                    if (200 == retCode) {
                        ret = new ExplainedBoolean(true, "Close Door Request OK.");
                    } else {
                        ret = new ExplainedBoolean(false, "Close Door failed: " + retCode);
                    }
                } catch (IOException ex) {
                    LOG.info("Exception in close door: " + ex.getMessage());
                    ret = new ExplainedBoolean(false, "Close Door exception:" + ex.getMessage());
                }
                if (!ret.getValue()) {
                    break;
                }
            }
        }

        return ret;
    }
}