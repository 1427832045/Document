package org.opentcs.kernel.vehicles.synchronizer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.annotation.Nonnull;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seer.srd.route.RouteConfigKt;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.synchronizer.QueryBefore;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryDoorOpen implements QueryBefore {

    private static final Logger LOG = LoggerFactory.getLogger(QueryDoorOpen.class);
    private static final String passDoor = "device:passDoor";

    private final String urlBase;

    @Inject
    public QueryDoorOpen() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getDoorRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {

        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(passDoor)) {
            String[] doors = props.getOrDefault(passDoor, "UnknowDoor").split(",");
            final String actualUrl = props.getOrDefault(SPECIFIED_SERVER_ROUTE, urlBase);
            for (String doorName : doors) {
                String url = actualUrl +
                        "doors/" +
                        doorName;
                try {
                    HttpResponse response = Request.Get(url).connectTimeout(5000)
                            .socketTimeout(5000)
                            .execute()
                            .returnResponse();
                    int retCode = response.getStatusLine().getStatusCode();
                    if (200 == retCode) {
                        InputStream jsonStream = response.getEntity().getContent();
                        ObjectMapper mapper = new ObjectMapper();
                        Door door = mapper.readValue(jsonStream, Door.class);
                        if (door.getName().equals(doorName) &&
                                door.getStatus().equals(DoorStatus.OPEN)) {
                            ret = new ExplainedBoolean(true, String.format("%s is open.", door.getName()));
                        } else {
                            ret = new ExplainedBoolean(false, String.format("%s is %s.", door.getName(), door.getStatus().name()));
                        }
                    } else {
                        ret = new ExplainedBoolean(false, String.format("Query door failed: %d.", retCode));
                    }
                } catch (IOException | NullPointerException ex) {
                    LOG.info("Exception in query door: " + ex.getMessage());
                    ret = new ExplainedBoolean(false, "Query Door exception:" + ex.getMessage());
                }
                if (!ret.getValue()) {
                    break;
                }
            }
        }

        return ret;
    }

    public static class Door {
        private String name;
        private DoorStatus status;

        void setName(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }

        void setStatus(DoorStatus status) {
            this.status = status;
        }

        DoorStatus getStatus() {
            return status;
        }
    }

    public enum DoorStatus {
        OPEN,
        OPENING,
        CLOSE,
        CLOSING,
        ERROR,  // The door gets a fault itself
        TIMEOUT // The Device Synchroniser can not communation with the door
    }
}