package org.opentcs.kernel.vehicles.synchronizer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.annotation.Nonnull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommonLocationQuery {

    private CommonLocationQuery() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(CommonLocationQuery.class);

    public static ExplainedBoolean request(@Nonnull String vehicle,
                                           @Nonnull MovementCommand command,
                                           @Nonnull String queryKey,
                                           @Nonnull String specifiedRouteKey,
                                           @Nonnull String urlBase) {

        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(queryKey)) {
            final String locDescription = props.getOrDefault(queryKey, "Unknown:null");
            String locDeviceName;
            String locDeviceAction;
            try {
                String[] locDescriptionArray = locDescription.split(":");
                locDeviceName = locDescriptionArray[0];
                locDeviceAction = locDescriptionArray[1];
                String url = props.getOrDefault(specifiedRouteKey, urlBase) +
                        "locationDevices/" +
                        locDeviceName +
                        "?action=" +
                        locDeviceAction;
                LOG.info("{} Query GET {}", vehicle, url);
                HttpResponse response = Request.Get(url).connectTimeout(5000)
                        .socketTimeout(5000)
                        .execute()
                        .returnResponse();
                int retCode = response.getStatusLine().getStatusCode();
                if (200 == retCode) {
                    InputStream jsonStream = response.getEntity().getContent();
                    ObjectMapper mapper = new ObjectMapper();
                    LocationDevice locDevice = mapper.readValue(jsonStream, LocationDevice.class);
                    if (locDevice.getName().equals(locDeviceName) &&
                            locDevice.getLastAction().equals(locDeviceAction) &&
                            locDevice.getLastActionStatus().equals(ActionStatus.DONE)) {
                        ret = new ExplainedBoolean(true, String.format("%s query %s is ok.", vehicle, locDevice.getName()));
                    } else {
                        ret = new ExplainedBoolean(false, String.format("%s query %s, action:%s get state %s.",
                                vehicle,
                                locDevice.getName(),
                                locDevice.getLastAction(),
                                locDevice.getLastActionStatus().name()));
                        if (locDevice.getLastActionStatus().equals(ActionStatus.FAILED) ||
                                locDevice.getStatus().equals(Status.ERROR) ||
                                locDevice.getStatus().equals(Status.TIMEOUT)) {
                            // TODO reporter.error(locDeviceName, ret.getReason());
                        }
                    }
                } else {
                    ret = new ExplainedBoolean(false, String.format("%s query %s failed: %d.",
                            vehicle,
                            locDeviceName,
                            retCode));
                    // TODO reporter.error(locDeviceName, ret.getReason());
                }
            } catch (IOException | NullPointerException | ArrayIndexOutOfBoundsException ex) {
                LOG.info("Exception in query location command's properties: " + ex.getMessage());
                ret = new ExplainedBoolean(false, "Query location command's exception: " + ex.getMessage());
                // TODO reporter.error(locDeviceName, ret.getReason());
            }
        }

        return ret;
    }

    public static class LocationDevice {

        private String name;
        private String lastAction;
        private ActionStatus lastActionStatus;
        private Status status;

        void setName(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }

        void setLastAction(String lastAction) {
            this.lastAction = lastAction;
        }

        String getLastAction() {
            return lastAction;
        }

        void setLastActionStatus(ActionStatus lastActionStatus) {
            this.lastActionStatus = lastActionStatus;
        }

        ActionStatus getLastActionStatus() {
            return lastActionStatus;
        }

        void setStatus(Status status) {
            this.status = status;
        }

        Status getStatus() {
            return status;
        }
    }

    public enum ActionStatus {
        RAW,
        DONE,
        EXECUTING,
        FAILED
    }

    enum Status {
        IDLE,
        EXECUTING,
        ERROR,
        TIMEOUT
    }
}