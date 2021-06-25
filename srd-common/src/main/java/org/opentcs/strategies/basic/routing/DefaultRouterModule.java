package org.opentcs.strategies.basic.routing;

import com.seer.srd.route.EvaluatorType;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.ShortestPathConfiguration;
import org.opentcs.customizations.kernel.KernelInjectionModule;
import org.opentcs.strategies.basic.routing.jgrapht.BellmanFordPointRouterFactory;
import org.opentcs.strategies.basic.routing.jgrapht.DefaultModelGraphMapper;
import org.opentcs.strategies.basic.routing.jgrapht.DijkstraPointRouterFactory;
import org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluator;
import org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluatorComposite;
import org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluatorDistance;
import org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluatorExplicitProperties;
import org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluatorHops;
import org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluatorTravelTime;
import org.opentcs.strategies.basic.routing.jgrapht.FloydWarshallPointRouterFactory;
import org.opentcs.strategies.basic.routing.jgrapht.ModelGraphMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guice configuration for the default router.
 */
public class DefaultRouterModule extends KernelInjectionModule {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRouterModule.class);

    @Override
    protected void configure() {
        configureRouterDependencies();
        bindRouter(DefaultRouter.class);
    }

    private void configureRouterDependencies() {
        ShortestPathConfiguration spConfiguration = RouteConfigKt.getRouteConfig().getShortestPath();
        bind(ShortestPathConfiguration.class)
                .toInstance(spConfiguration);

        bind(ModelGraphMapper.class)
                .to(DefaultModelGraphMapper.class);

        switch (spConfiguration.getAlgorithm()) {
            case DIJKSTRA:
                bind(PointRouterFactory.class)
                        .to(DijkstraPointRouterFactory.class);
                break;
            case BELLMAN_FORD:
                bind(PointRouterFactory.class)
                        .to(BellmanFordPointRouterFactory.class);
                break;
            case FLOYD_WARSHALL:
                bind(PointRouterFactory.class)
                        .to(FloydWarshallPointRouterFactory.class);
                break;
            default:
                LOG.warn("Unhandled algorithm selected ({}), falling back to Dijkstra's algorithm.",
                        spConfiguration.getAlgorithm());
                bind(PointRouterFactory.class)
                        .to(DijkstraPointRouterFactory.class);
        }

        bind(EdgeEvaluator.class)
                .toProvider(() -> {
                    EdgeEvaluatorComposite result = new EdgeEvaluatorComposite();
                    for (EvaluatorType type : spConfiguration.getEdgeEvaluators()) {
                        result.getComponents().add(toEdgeEvaluator(type));
                    }
                    // Make sure at least one evaluator is used.
                    if (result.getComponents().isEmpty()) {
                        LOG.warn("No edge evaluator enabled, falling back to distance-based evaluation.");
                        result.getComponents().add(new EdgeEvaluatorDistance());
                    }
                    return result;
                });
    }

    @SuppressWarnings("deprecation")
    private EdgeEvaluator toEdgeEvaluator(EvaluatorType type) {
        switch (type) {
            case DISTANCE:
                return new EdgeEvaluatorDistance();
            case TRAVELTIME:
                return new EdgeEvaluatorTravelTime();
            case HOPS:
                return new EdgeEvaluatorHops();
            case EXPLICIT:
                return new org.opentcs.strategies.basic.routing.jgrapht.EdgeEvaluatorExplicit();
            case EXPLICIT_PROPERTIES:
                return new EdgeEvaluatorExplicitProperties();
            default:
                throw new IllegalArgumentException("Unhandled evaluator type: " + type);
        }
    }

}
