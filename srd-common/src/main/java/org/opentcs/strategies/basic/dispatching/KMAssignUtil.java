package org.opentcs.strategies.basic.dispatching;

import com.seer.srd.BusinessError;
import com.seer.srd.model.Point;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.components.kernel.Router;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.TransportOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;

/**
 * 自己实现的Kuhn-Munkres算法。使用图表示而不是矩阵表示。
 * <p>
 * 车和任务匹配的特殊情况：
 * 所有的车都可以执行所有的任务，即，任何一个任务节点都有连接到所有车辆节点的连线，反之亦然；
 * 因此，不需要寻找增广路，任何一个任务点都直接和任意一个车辆点相连
 */
public class KMAssignUtil {

    private final Router router;

    private final TransportOrderUtil transportOrderUtil;

    public static final Logger LOG = LoggerFactory.getLogger(KMAssignUtil.class);

    @Inject
    public KMAssignUtil(Router router, TransportOrderUtil transportOrderUtil) {
        this.router = requireNonNull(router, "router");
        this.transportOrderUtil = requireNonNull(transportOrderUtil, "transportOrderUtil");
    }

    public void assign(Set<Vehicle> vehicles, Set<TransportOrder> orders) {
        try {
            AssignGraph g = buildGraph(vehicles, orders);
            g.calculateMatch();
            LOG.info("Kuhn-Munkres assign, start");
            // 车分配完了，整个分配过程也就结束了
            for (int i = 0; i < g.X.size(); i++) {
                AssignGraphVertex v = g.X.get(i);

                Vehicle vehicle = VehicleService.INSTANCE.getVehicle(v.name);
                LOG.debug("vehicle name: " + v.name);

                AssignGraphVertex t = g.Y.get(g.match[v.id]);
                if (t == null) continue; // 这辆车没有匹配的任务
                TransportOrder transportOrder = TransportOrderService.INSTANCE.getOrder(t.name);
                LOG.debug("order name: " + t.name + " destination of first drive order: " +
                        transportOrder.getDriveOrders().get(0).getDestination().getDestination());

                if (vehicle.getCurrentPosition() == null)
                    throw new BusinessError("vehicle position is null" + v.name, null);
                Point srcPoint = PlantModelService.INSTANCE.getPointIfNameIs(vehicle.getCurrentPosition());
                if (srcPoint == null)
                    throw new BusinessError("vehicle srcPoint is null, vehicle: " + v.name + ", srcPoint: "
                            + vehicle.getCurrentPosition(), null);

                // TODO 缓存之前算过的optionalDriveOrders
                Optional<List<DriveOrder>> optionalDriveOrders = router.getRoute(vehicle, srcPoint, transportOrder);
                if (!optionalDriveOrders.isPresent())
                    throw new BusinessError("no route for order: " + t.name + ", and vehicle: " + v.name, null);
                List<DriveOrder> driveOrders = optionalDriveOrders.get();

                // 实际分配任务
                transportOrderUtil.assignTransportOrder(vehicle, transportOrder, driveOrders);

                LOG.info("Kuhn-Munkres assign, end");
            }
        } catch (Exception e) {
            LOG.error("Failed to assign", e);
        }
    }

    /**
     * 根据给定的一组任务和车辆创建用于匹配的二分图，主要是计算边的权重
     *
     * @param vehicles 执行任务的车辆
     * @param orders   需要执行的任务
     * @return 节点为车辆和任务，所有的边都是一端为车辆，一端为任务，权重为匹配“花费”的图。
     */
    public AssignGraph buildGraph(Set<Vehicle> vehicles, Set<TransportOrder> orders) {

        AssignGraph g = new AssignGraph();

        g.X = new HashMap<>();
        g.Y = new HashMap<>();
        AtomicInteger id = new AtomicInteger(-1);

        // 添加节点
        vehicles.forEach(vehicle -> {
            // 添加车辆到X集合
            AssignGraphVertex n = new AssignGraphVertex();
            n.id = id.incrementAndGet();
            n.label = 0;
            n.next = new HashSet<>(); // 每辆车都可以执行所有的任务
            n.type = ASSIGN_GRAPH_VERTEX_TYPE_VEHICLE;
            n.name = vehicle.getName();

            g.X.put(n.id, n);
        });
        orders.forEach(order -> {
            // 添加任务到Y集合
            AssignGraphVertex n = new AssignGraphVertex();
            n.id = id.incrementAndGet();
            n.label = 0;
            n.next = new HashSet<>();
            n.type = ASSIGN_GRAPH_VERTEX_TYPE_ORDER;
            n.name = order.getName();

            g.Y.put(n.id, n);
        });
        // 添加边
        AtomicLong maxCost = new AtomicLong();
        g.X.values().forEach(vX -> g.Y.values().forEach(vY -> {
            // 计算从机器人当前位置到任务第一个DriveOrder目标点的距离，作为边的权重
            // 获取机器人的当前位置
            Vehicle v = VehicleService.INSTANCE.getVehicle(vX.name);
            TransportOrder t = TransportOrderService.INSTANCE.getOrder(vY.name);
            if (v.getCurrentPosition() == null)
                throw new BusinessError("no current position, vehicle: " + v.getName(), null);
            Point currentPoint = PlantModelService.INSTANCE.getPointIfNameIs(v.getCurrentPosition());
            if (currentPoint == null) throw new BusinessError("no current point", null);
            // 计算路径
            Optional<List<DriveOrder>> optionalRoute = router.getRoute(v, currentPoint, t);
            if (!optionalRoute.isPresent())
                throw new BusinessError("no route, vehicle: " + v.getName() + ", order: " + t, null);
            Route r = optionalRoute.get().get(0).getRoute();
            if (r == null) throw new BusinessError("no route", null);
            // 添加边
            long cost = r.getCosts();
            LOG.debug("vehicle: " + vX.name + ", current position: " + currentPoint.getName() + ", destination: " +
                    r.getFinalDestinationPoint() + ", cost: " + cost);
            maxCost.set(max(cost, maxCost.get()));
            g.addEdge(vX.id, vY.id, cost);
        }));
        // 初始标记 Y 中的标记都为0，X中的标记都为最大边权重，此时相等子图只有一条边
        g.X.values().forEach(v -> v.label = maxCost.get());
        // 生成初始匹配 初始匹配可以为空
        g.numVertices = id.get() + 1;
        g.match = new int[g.numVertices];

        for (int i = 0; i < g.numVertices; i++) {
            g.match[i] = -1;
        }
        return g;
    }

    static class AssignGraph {

        AssignGraph() {
            E = new HashSet<>();
            X = new HashMap<>();
            Y = new HashMap<>();
        }

        int numVertices;
        // TODO 用数组代替map
        Map<Integer, AssignGraphVertex> X;
        Map<Integer, AssignGraphVertex> Y;
        Set<AssignGraphEdge> E;
        int[] match; // match[i] 为 i的匹配点，为-1时没有匹配

        // 根据当前的标记生成相等子图
        AssignGraph buildEqualityGraph() {
            AssignGraph equalityGraph = new AssignGraph();
            equalityGraph.X = X;
            equalityGraph.Y = Y;

            equalityGraph.E = new HashSet<>();
            E.forEach(edge -> {
                // 过滤出两端标记值相加等于边的权重的边，这些边构成相等子图的边集合
                long xLabel = X.get(edge.xEnd).label;
                long yLabel = Y.get(edge.yEnd).label;
                if (xLabel + yLabel == edge.weight) {
                    equalityGraph.E.add(edge);
                }
            });

            return equalityGraph;
        }

        /* 判断是否是完美匹配 */
        boolean isPerfectMatch() {
            for (int j : match) {
                if (j == -1) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 添加一条边，连接X中的一个点和Y中的一个点。
         *
         * @param xEnd   X中的一个点的id
         * @param yEnd   Y中的一个点的id
         * @param weight 边的权重
         */
        void addEdge(int xEnd, int yEnd, long weight) {
            AssignGraphEdge e = new AssignGraphEdge();
            e.xEnd = xEnd;
            e.yEnd = yEnd;
            e.weight = weight;
            E.add(e);
            X.get(xEnd).next.add(yEnd);
            Y.get(yEnd).next.add(xEnd);
        }

        /**
         * 给定点集s，求Next(s) = {x: 存在 e 属于 E，使得 e.xEnd 属于s}
         *
         * @param s 点集
         * @return 存在一条边和s中点相连的，Y中的点
         */
        Set<Integer> next(Set<Integer> s) {
            Set<Integer> result = new HashSet<>();
            s.forEach(id -> result.addAll(X.get(id).next));
            return result;
        }

        /* 更新标记值，扩大相等子图；每次更新至少增加一条边。*/
        void updateLabeling(Set<Integer> s, Set<Integer> t) {
            long alpha = Long.MAX_VALUE;
            // 过滤出合适的边
            Set<AssignGraphEdge> e = E.stream()
                    .filter(edge -> s.contains(edge.xEnd) && !t.contains(edge.yEnd))
                    .collect(Collectors.toSet());
            // 计算alpha
            for (AssignGraphEdge edge : e) {
                alpha = Math.min(X.get(edge.xEnd).label + Y.get(edge.yEnd).label - edge.weight, alpha);
            }
            // 更新标记值
            long finalAlpha = alpha;
            s.forEach(id -> X.get(id).label -= finalAlpha);
            t.forEach(id -> Y.get(id).label += finalAlpha);
        }

        /* 执行匈牙利算法，进行匹配，结果存在match数组中。*/
        void calculateMatch() {
            Set<Integer> s = new HashSet<>();
            Set<Integer> t = new HashSet<>();

            while (true) {
                // 检查是不是完美匹配
                AssignGraph equalityGraph = this.buildEqualityGraph();
                if (isPerfectMatch()) {
                    LOG.debug("perfect match");
                    break;
                }
                // 检查 X 和 Y 是不是匹配完了
                if (s.containsAll(X.keySet()) && X.keySet().containsAll(s)) {
                    LOG.debug("X = S");
                    break;
                }
                if (t.containsAll(Y.keySet()) && Y.keySet().containsAll(t)) {
                    LOG.debug("Y = T");
                    break;
                }
                // 选择一个X中的未匹配点
                int u = -1;
                for (Integer i : X.keySet()) {
                    if (match[i] == -1) {
                        // 加入S
                        s.add(i);
                        u = i;
                        break;
                    }
                }
                // 判断Next_l(S)是否等于T
                Set<Integer> ns = equalityGraph.next(s);
                if (ns.containsAll(t) && t.containsAll(ns)) {
                    // 等于
                    LOG.debug("N_l(S) = T");
                    updateLabeling(s, t);
                } else {
                    LOG.debug("N_l(S) != T");
                    ns.removeAll(t);

                    Optional<Integer> optionalY = ns.stream().findFirst();
                    if (!optionalY.isPresent()) throw new BusinessError("N_l(S) - T is empty!", null);
                    Integer y = optionalY.get();

                    if (match[y] == -1) {
                        // 完全二分图，不需要寻找增广路，直接修改匹配
                        match[y] = u;
                        match[u] = y;
                    } else {
                        Integer z = match[y];
                        s.add(z);
                        t.add(y);
                    }
                }
            }
        }
    }

    // TODO equals() hash()
    static class AssignGraphVertex {
        int id; // 用于区分不同的节点，id唯一
        String name;
        String type; // Vehicle / TransportOrder
        long label; // 匈牙利算法中的标记值
        Set<Integer> next;
        // 从这个节点出发，可以到达的节点，保存连通信息，不是匈牙利算法中在一个标记下的Equality Graph中的N_l函数
    }

    static final String ASSIGN_GRAPH_VERTEX_TYPE_VEHICLE = "vehicle";
    static final String ASSIGN_GRAPH_VERTEX_TYPE_ORDER = "transportOrder";

    static class AssignGraphEdge {
        int xEnd, yEnd;
        long weight;
    }
}
