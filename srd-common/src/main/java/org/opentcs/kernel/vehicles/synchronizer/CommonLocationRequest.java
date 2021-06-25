/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.kernel.vehicles.synchronizer;

import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.apache.http.client.fluent.Request;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommonLocationRequest {

  private CommonLocationRequest() {
  }

  private static final Logger LOG = LoggerFactory.getLogger(CommonLocationRequest.class);

  public static ExplainedBoolean request( @Nonnull String vehicle, 
                                          @Nonnull MovementCommand command,
                                          @Nonnull String requestKey,
                                          @Nonnull String specifiedRouteKey,
                                          @Nonnull String urlBase) {

    Map<String, String> props = command.getProperties();
    ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

    if (props.containsKey(requestKey)) {
      String locDescription = props.getOrDefault(requestKey, "Unknown:null");
      try {
        String[] locDescriptionArray = locDescription.split(":");
        String locDeviceName = locDescriptionArray[0];
        String locDeviceAction = locDescriptionArray[1];
        String url = props.getOrDefault(specifiedRouteKey, urlBase) +
                "locationDevices/" +
                locDeviceName +
                "?action=" +
                locDeviceAction;
        LOG.info("{} Request POST {}", vehicle, url);
        HttpResponse response = Request.Post(url).connectTimeout(5000)
                                                 .socketTimeout(5000)
                                                 .execute()
                                                 .returnResponse();
        int retCode = response.getStatusLine().getStatusCode();
        if (200 == retCode) {
          ret = new ExplainedBoolean(true, String.format("%s request %s is ok.", vehicle, locDeviceName));
        }
        else {
          ret = new ExplainedBoolean(false, String.format("%s request %s, action:%s get retcode %d.", 
                                                          vehicle, 
                                                          locDeviceName, 
                                                          locDeviceAction,
                                                          retCode));
        }
      }
      catch (IOException ex) {
        LOG.info("Exception in location device request: " + ex.getMessage());
        ret = new ExplainedBoolean(false, "Location device request exception:" + ex.getMessage());
      }
      catch (ArrayIndexOutOfBoundsException ex) {
        LOG.info("Exception in request location command's properties: " + ex.getMessage());
        ret = new ExplainedBoolean(false, "Request location command's exception: " + ex.getMessage());
      }
    }

    return ret;
  }
}