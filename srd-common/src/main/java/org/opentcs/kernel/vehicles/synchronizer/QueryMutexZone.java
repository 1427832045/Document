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

public class QueryMutexZone implements QueryBefore {

    private static final Logger LOG = LoggerFactory.getLogger(QueryMutexZone.class);
    private static final String zoneName = "device:enterMutexZone";

    private final String urlBase;

    @Inject
    public QueryMutexZone() {
        this.urlBase = RouteConfigKt.getRouteConfig().getSynchronizer().getMutexZoneRoute();
    }

    public ExplainedBoolean request(@Nonnull String vehicle, @Nonnull MovementCommand command) {

        Map<String, String> props = command.getProperties();
        ExplainedBoolean ret = new ExplainedBoolean(true, "No related key words.");

        if (props.containsKey(zoneName)) {
            String[] zones = props.getOrDefault(zoneName, "UnknownZone").split(",");
            final String actualUrl = props.getOrDefault(SPECIFIED_SERVER_ROUTE, urlBase);
            for (String name : zones) {
                String url = actualUrl +
                        "mutexZones/" +
                        name;
                try {
                    HttpResponse response = Request.Get(url).connectTimeout(5000)
                            .socketTimeout(5000)
                            .execute()
                            .returnResponse();
                    int retCode = response.getStatusLine().getStatusCode();
                    if (200 == retCode) {
                        InputStream jsonStream = response.getEntity().getContent();
                        ObjectMapper mapper = new ObjectMapper();
                        MutexZone zone = mapper.readValue(jsonStream, MutexZone.class);
                        if (zone.getName().equals(name) &&
                                zone.getStatus() > 0) {
                            ret = new ExplainedBoolean(true, String.format("%s is got.", zone.getName()));
                        } else {
                            ret = new ExplainedBoolean(false, String.format("%s is %s.", zone.getName(), zone.getStatus()));
                        }
                    } else {
                        ret = new ExplainedBoolean(false, String.format("Query mutex zone failed: %d.", retCode));
                    }
                } catch (IOException | NullPointerException ex) {
                    LOG.info("Exception in query mutex zone: " + ex.getMessage());
                    ret = new ExplainedBoolean(false, "Query mutex zone exception:" + ex.getMessage());
                }
                if (!ret.getValue()) {
                    break;
                }
            }
        }

        return ret;
    }

    public static class MutexZone {
        private String name;
        private int status;

        void setName(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }

        void setStatus(int status) {
            this.status = status;
        }

        int getStatus() {
            return status;
        }
    }
}