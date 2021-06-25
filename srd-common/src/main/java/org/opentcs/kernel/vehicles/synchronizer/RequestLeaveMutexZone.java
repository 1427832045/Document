/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.kernel.vehicles.synchronizer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.net.URLEncoder;
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

public class RequestLeaveMutexZone implements RequestAfterExecuted {

    private static final Logger LOG = LoggerFactory.getLogger(RequestLeaveMutexZone.class);
    private static final String zoneName = "device:leaveMutexZone";

    private final String urlBase;

    @Inject
    public RequestLeaveMutexZone() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getMutexZoneRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {
        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(zoneName)) {
            String vehicleName = vehicle;
            // encode for chinese
            try {
                vehicleName = URLEncoder.encode(vehicle, "utf-8");
            } catch (UnsupportedEncodingException ex) {
                ex.printStackTrace();
                LOG.info("Exception in encode vehicle's name: " + ex.getMessage());
            }

            String[] zones = props.getOrDefault(zoneName, "UnknownZone").split(",");
            final String actualUrl = props.getOrDefault(SPECIFIED_SERVER_ROUTE, urlBase);
            for (String zone : zones) {
                String url = actualUrl +
                        "mutexZones/" +
                        zone +
                        "?action=leave&vehicle=" +
                        vehicleName;
                try {
                    HttpResponse response = Request.Post(url).connectTimeout(5000)
                            .socketTimeout(5000)
                            .execute()
                            .returnResponse();
                    int retCode = response.getStatusLine().getStatusCode();
                    if (200 == retCode) {
                        ret = new ExplainedBoolean(true, "Leave zone Request OK.");
                    } else {
                        ret = new ExplainedBoolean(false, "Leave zone failed: " + retCode);
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                    LOG.info("Exception in leave zone: " + ex.getMessage());
                    ret = new ExplainedBoolean(false, "Leave zone exception:" + ex.getMessage());
                }
                if (!ret.getValue()) {
                    break;
                }
            }
        }

        return ret;
    }
}