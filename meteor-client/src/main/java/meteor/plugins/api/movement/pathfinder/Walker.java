package meteor.plugins.api.movement.pathfinder;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import meteor.plugins.api.commons.Rand;
import meteor.plugins.api.commons.Time;
import meteor.plugins.api.entities.Players;
import meteor.plugins.api.game.Game;
import meteor.plugins.api.game.GameThread;
import meteor.plugins.api.movement.Movement;
import meteor.plugins.api.scene.Tiles;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import org.jetbrains.annotations.NotNull;
import org.sponge.util.Logger;

import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

@Singleton
public class Walker {
    private static final Logger logger = new Logger("Walker");
    private static final int MAX_INTERACT_DISTANCE = 20;
    private static final int MIN_TILES_WALKED_IN_STEP = 7;
    private static final int MAX_TILES_WALKED_IN_STEP = 14;
    private static final int MIN_TILES_WALKED_BEFORE_RECHOOSE = 10;
    private static final int MIN_TILES_LEFT_BEFORE_RECHOOSE = 3;
    private static final int MAX_MIN_ENERGY = 50;
    private static final int MIN_ENERGY = 5;
    public static final CollisionMap COLLISION_MAP;
    public static final LoadingCache<WorldPoint, List<WorldPoint>> PATH_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build(new CacheLoader<>() {
                @Override
                public List<WorldPoint> load(@NotNull WorldPoint key) {
                    return buildPath(key);
                }
            });

    static {
        CollisionMap loaded;
        try {
            loaded = new CollisionMap(
                    new GZIPInputStream(
                            new ByteArrayInputStream(
                                    Walker.class.getResourceAsStream("/collision-map").readAllBytes()
                            )
                    ).readAllBytes()
            );
        } catch (IOException e) {
            e.printStackTrace();
            loaded = null;
        }

        COLLISION_MAP = loaded;
    }

    public static boolean walkTo(WorldPoint destination) {
        Player local = Players.getLocal();
        if (destination.equals(local.getWorldLocation())) {
            return true;
        }

        Map<WorldPoint, List<Transport>> transports = buildTransportLinks();
        LinkedHashMap<WorldPoint, Teleport> teleports = buildTeleportLinks(destination);
        List<WorldPoint> path;

        try {
            path = PATH_CACHE.get(destination);
        } catch (ExecutionException e) {
            logger.error("Failed to get cached path", e);
            return false;
        }

        if (path == null) {
            logger.error("Path is null");
            return false;
        }

        if (path.isEmpty()) {
            logger.error("Path was empty");
            return false;
        }

        // Refresh path if our direction changed
        if (!path.contains(Players.getLocal().getWorldLocation())) {
            PATH_CACHE.refresh(destination);
            return false;
        }

        WorldPoint startPosition = path.get(0);
        Teleport teleport = teleports.get(startPosition);

        if (teleport != null) {
            teleport.getHandler().run();
            Time.sleep(5000);
            return false;
        }

        return walkAlong(destination, path, transports);
    }

    public static boolean walkAlong(WorldPoint destination, List<WorldPoint> path, Map<WorldPoint, List<Transport>> transports) {
        Player local = Players.getLocal();
        WorldPoint nextTile = path.get(path.size() - 1);
        if (local.getWorldLocation().distanceTo(nextTile) > 0) {
            List<WorldPoint> remainingPath = remainingPath(path);

            if (handleTransports(remainingPath, transports)) {
                return false;
            }

            return stepAlong(remainingPath);
        }

        // Refresh the cached path
        PATH_CACHE.refresh(destination);
        return false;
    }

    public static boolean stepAlong(List<WorldPoint> path) {
        List<WorldPoint> reachablePath = reachablePath(path);
        if (reachablePath.isEmpty()) {
            return false;
        }
        int nextTileIdx = reachablePath.size() - 1;
        if (nextTileIdx <= MIN_TILES_WALKED_IN_STEP) {
            return step(reachablePath.get(nextTileIdx));
        }

        if (nextTileIdx > MAX_TILES_WALKED_IN_STEP) {
            nextTileIdx = MAX_TILES_WALKED_IN_STEP;
        }

        int targetDistance = Rand.nextInt(MIN_TILES_WALKED_IN_STEP, nextTileIdx);
        return step(reachablePath.get(targetDistance));
    }

    public static List<WorldPoint> reachablePath(List<WorldPoint> remainingPath) {
        Player local = Players.getLocal();
        List<WorldPoint> out = new ArrayList<>();
        for (WorldPoint p : remainingPath) {
            Tile tile = Tiles.getTiles(x -> x.getWorldLocation().equals(p)).stream().findFirst().orElse(null);
            if (tile == null) {
                break;
            }

            out.add(p);
        }

        if (out.isEmpty() || out.size() == 1 && out.get(0).equals(local.getWorldLocation())) {
            return Collections.emptyList();
        }

        return out;
    }

    public static boolean step(WorldPoint destination) {
        Player local = Players.getLocal();
        logger.debug("Stepping towards " + destination);
        Movement.walk(destination);

        if (local.getWorldLocation().equals(destination)) {
            return false;
        }

        if (!Movement.isRunEnabled() && (Game.getClient().getEnergy() >= Rand.nextInt(MIN_ENERGY, MAX_MIN_ENERGY) || (local.getHealthScale() > -1 && Game.getClient().getEnergy() > 0))) {
            Movement.toggleRun();
            Time.sleepUntil(Movement::isRunEnabled, 2000);
            return true;
        }

        if (!Movement.isRunEnabled() && Game.getClient().getEnergy() > 0 && Movement.isStaminaBoosted()) {
            Movement.toggleRun();
            Time.sleepUntil(Movement::isRunEnabled, 2000);
            return true;
        }

        return true;
    }

    public static int recalculateDistance(int targetDistance) {
        int rechoose = MIN_TILES_WALKED_BEFORE_RECHOOSE + Rand.nextInt(0, targetDistance - MIN_TILES_WALKED_BEFORE_RECHOOSE + 1);
        rechoose = Math.min(rechoose, targetDistance - MIN_TILES_LEFT_BEFORE_RECHOOSE);
        return rechoose;
    }

    public static boolean handleTransports(List<WorldPoint> path, Map<WorldPoint, List<Transport>> transports) {
        Player local = Players.getLocal();
        for (int i = 0; i < MAX_INTERACT_DISTANCE; i++) {
            if (i + 1 >= path.size()) {
                break;
            }

            WorldPoint a = path.get(i);
            WorldPoint b = path.get(i + 1);
            Tile tileA = Tiles.getTiles(x -> x.getWorldLocation().equals(a)).stream().findFirst().orElse(null);
            if (tileA == null) {
                return false;
            }

            List<Transport> transportTargets = transports.get(a);
            if (transportTargets != null) {
                Transport transport = transportTargets.stream().filter(x -> x.getDestination().equals(b)).findFirst().orElse(null);

                if (transport != null && local.getWorldLocation().distanceTo(transport.getSource()) <= transport.getSourceRadius()) {
                    logger.debug("Trying to use transport at {}", transport.getSource());
                    transport.getHandler().run();
                    Time.sleep(2800);
                    return true;
                }
            }

            Tile tileB = Tiles.getTiles(x -> x.getWorldLocation().equals(b)).stream().findFirst().orElse(null);
            if (tileB == null) {
                return false;
            }

            if (isDoored(tileA, tileB)) {
                WallObject wall = tileA.getWallObject();
                wall.interact("Open");
                logger.debug("Handling door {}", wall.getWorldLocation());
                Time.sleepUntil(() -> tileA.getWallObject() == null
                        || !wall.hasAction("Open"), 2000);
                return true;
            }

            if (isDoored(tileB, tileA)) {
                WallObject wall = tileB.getWallObject();
                wall.interact("Open");
                logger.debug("Handling door {}", wall.getWorldLocation());
                Time.sleepUntil(() -> tileB.getWallObject() == null
                        || !wall.hasAction("Open"), 2000);
                return true;
            }
        }

        return false;
    }

    public static boolean isDoored(Tile source, Tile destination) {
        WallObject wall = source.getWallObject();
        if (wall == null) {
            return false;
        }

        if (!wall.isDefinitionCached()) {
            GameThread.invokeLater(() -> Game.getClient().getObjectComposition(wall.getId()));
        }

        return isWalled(source, destination) && wall.hasAction("Open");
    }

    public static boolean isWalled(Tile source, Tile destination) {
        WallObject wall = source.getWallObject();
        if (wall == null) {
            return false;
        }

        WorldPoint a = source.getWorldLocation();
        WorldPoint b = destination.getWorldLocation();

        return switch (wall.getOrientationA()) {
            case 1 -> a.dx(-1).equals(b) || a.dx(-1).dy(1).equals(b) || a.dx(-1).dy(-1).equals(b);
            case 2 -> a.dy(1).equals(b) || a.dx(-1).dy(1).equals(b) || a.dx(1).dy(1).equals(b);
            case 4 -> a.dx(1).equals(b) || a.dx(1).dy(1).equals(b) || a.dx(1).dy(-1).equals(b);
            case 8 -> a.dy(-1).equals(b) || a.dx(-1).dy(-1).equals(b) || a.dx(-1).dy(1).equals(b);
            default -> false;
        };
    }

    public static List<WorldPoint> remainingPath(List<WorldPoint> path) {
        Player local = Game.getClient().getLocalPlayer();
        if (local == null) {
            return Collections.emptyList();
        }

        var nearest = path.stream().min(Comparator.comparingInt(x -> x.distanceTo(local.getWorldLocation())))
                .orElse(null);
        if (nearest == null) {
            return Collections.emptyList();
        }

        return path.subList(path.indexOf(nearest), path.size());
    }

    public static List<WorldPoint> calculatePath(
            List<WorldPoint> startPoints,
            WorldPoint destination,
            Map<WorldPoint, List<Transport>> transports
    ) {
        if (COLLISION_MAP == null) {
            return Collections.emptyList();
        }

        return new Pathfinder(COLLISION_MAP, transports, startPoints,
                destination).find();
    }

    public static Map<WorldPoint, List<Transport>> buildTransportLinks() {
        Map<WorldPoint, List<Transport>> out = new HashMap<>();
        for (Transport transport : TransportLoader.buildTransports()) {
            out.computeIfAbsent(transport.getSource(), x -> new ArrayList<>()).add(transport);
        }

        return out;
    }

    public static List<WorldPoint> buildPath(WorldPoint destination) {
        Player local = Players.getLocal();
        Map<WorldPoint, List<Transport>> transports = buildTransportLinks();
        LinkedHashMap<WorldPoint, Teleport> teleports = buildTeleportLinks(destination);
        List<WorldPoint> startPoints = new ArrayList<>(teleports.keySet());
        startPoints.add(local.getWorldLocation());

        return calculatePath(startPoints, destination, transports);
    }

    public static LinkedHashMap<WorldPoint, Teleport> buildTeleportLinks(WorldPoint destination) {
        LinkedHashMap<WorldPoint, Teleport> out = new LinkedHashMap<>();
        Player local = Players.getLocal();

        for (Teleport teleport : TeleportLoader.buildTeleports()) {
            if (teleport.getDestination().distanceTo(local.getWorldLocation()) > 50
                    && local.getWorldLocation().distanceTo(destination) > teleport.getDestination().distanceTo(destination) + 20) {
                out.putIfAbsent(teleport.getDestination(), teleport);
            }
        }

        return out;
    }
}
