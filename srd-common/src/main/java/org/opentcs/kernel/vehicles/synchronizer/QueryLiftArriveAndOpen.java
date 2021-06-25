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

public class QueryLiftArriveAndOpen implements QueryBefore {

    private static final Logger LOG = LoggerFactory.getLogger(QueryLiftArriveAndOpen.class);
    private static final String occupyLift = "device:occupyLift";
    private static final String unoccupyLift = "device:unoccupyLift";

    private final String urlBase;

    @Inject
    public QueryLiftArriveAndOpen() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getLiftRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {

        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(occupyLift) ||
                props.containsKey(unoccupyLift)) {

            final String liftDescription;
            if (props.containsKey(occupyLift)) {
                liftDescription = props.getOrDefault(occupyLift, "Unknown:null");
            } else {
                liftDescription = props.getOrDefault(unoccupyLift, "Unknown:null");
            }
            try {
                String[] liftDescriptionArray = liftDescription.split(":");
                String liftName = liftDescriptionArray[0];
                String liftFloor = liftDescriptionArray[1];
                String url = props.getOrDefault(SPECIFIED_SERVER_ROUTE, urlBase) +
                        "lifts/" +
                        liftName;
                HttpResponse response = Request.Get(url).connectTimeout(5000)
                        .socketTimeout(5000)
                        .execute()
                        .returnResponse();
                int retCode = response.getStatusLine().getStatusCode();
                if (200 == retCode) {
                    InputStream jsonStream = response.getEntity().getContent();
                    ObjectMapper mapper = new ObjectMapper();
                    Lift lift = mapper.readValue(jsonStream, Lift.class);
                    if (lift.getName().equals(liftName) &&
                            lift.getCurrentFloor().equals(liftFloor) &&
                            lift.getStatus().equals(DoorStatus.OPEN)) {
                        ret = new ExplainedBoolean(true, String.format("%s is open.", lift.getName()));
                    } else {
                        ret = new ExplainedBoolean(false, String.format("%s is %s %s.", lift.getName(),
                                lift.getCurrentFloor(),
                                lift.getStatus().name()));
                    }
                } else {
                    ret = new ExplainedBoolean(false, String.format("Query lift failed: %d.", retCode));
                }
            } catch (IOException | NullPointerException | ArrayIndexOutOfBoundsException ex) {
                LOG.info("Exception in query lift's properties: " + ex.getMessage());
                ret = new ExplainedBoolean(false, "Query lift's exception: " + ex.getMessage());
            }
        }

        return ret;
    }

    public static class Lift {
        private String name;
        private String currentFloor;
        private boolean isOccupy;
        private DoorStatus status;

        void setName(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }

        void setCurrentFloor(String currentFloor) {
            this.currentFloor = currentFloor;
        }

        String getCurrentFloor() {
            return currentFloor;
        }

        void setIsOccupy(boolean isOccupy) {
            this.isOccupy = isOccupy;
        }

        boolean getIsOccupy() {
            return isOccupy;
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
        ERROR,  // The lift gets a fault itself
        TIMEOUT // The Device Synchroniser can not communation with the lift
    }
}