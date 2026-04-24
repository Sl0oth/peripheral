package slooth.peripheral;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Client-side A* pathfinder + tick-based movement executor.
 * Path is calculated on a background thread, then executed each game tick
 * by injecting movement via NavigatorInput — no server-side mod needed.
 */
public class PeripheralNavigator {

    public static final PeripheralNavigator INSTANCE = new PeripheralNavigator();
    private PeripheralNavigator() {}

    public enum NavStatus { IDLE, PLANNING, RUNNING, DONE, FAILED, STUCK }

    private volatile NavStatus status     = NavStatus.IDLE;
    private volatile String    failReason = "";
    private volatile int       wpLeft     = 0;

    private final Object    lock    = new Object();
    private List<int[]>     path    = Collections.emptyList();
    private int             wpIdx   = 0;
    private int             goalX, goalY, goalZ, tolerance;

    private long    lastMoveMs  = 0;
    private double  lastX       = Double.NaN;
    private double  lastZ       = Double.NaN;
    private boolean jumpPending = false;

    // ── Public API ────────────────────────────────────────────────────────────

    public void navigateTo(int x, int y, int z, int tol) {
        synchronized (lock) {
            goalX = x; goalY = y; goalZ = z; tolerance = tol;
            status = NavStatus.PLANNING;
            failReason = "";
            path = Collections.emptyList();
            wpIdx = 0;
            lastX = Double.NaN;
            jumpPending = false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Thread t = new Thread(() -> {
            try {
                if (client.player == null || client.world == null) { fail("not_in_game"); return; }
                int sx = (int) Math.floor(client.player.getX());
                int sy = (int) Math.floor(client.player.getY());
                int sz = (int) Math.floor(client.player.getZ());
                List<int[]> found = astar(client, sx, sy, sz, x, y, z, 150_000);
                synchronized (lock) {
                    if (status != NavStatus.PLANNING) return;
                    if (found == null || found.isEmpty()) { fail("no_path_found"); return; }
                    path = found;
                    wpIdx = 0;
                    wpLeft = found.size();
                    lastMoveMs = System.currentTimeMillis();
                    status = NavStatus.RUNNING;
                    client.execute(() -> installInput(client));
                }
            } catch (Exception e) {
                fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "peripheral-astar");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        synchronized (lock) {
            status = NavStatus.IDLE;
            failReason = "";
            path = Collections.emptyList();
            wpLeft = 0;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> restoreInput(client));
    }

    public NavStatus getNavStatus()  { return status; }
    public String    getFailReason() { return failReason; }
    public int       getWpLeft()     { return wpLeft; }

    // ── Called every END_CLIENT_TICK ──────────────────────────────────────────

    public void tick(MinecraftClient client) {
        if (status != NavStatus.RUNNING) return;
        ClientPlayerEntity player = client.player;
        if (player == null) { fail("player_null"); return; }

        double cx = player.getX(), cy = player.getY(), cz = player.getZ();

        double dxG = goalX - cx, dzG = goalZ - cz;
        if (Math.sqrt(dxG * dxG + dzG * dzG) <= tolerance) {
            succeed(); restoreInput(client); return;
        }

        int[] wp;
        synchronized (lock) {
            while (wpIdx < path.size()) {
                int[] w = path.get(wpIdx);
                double wdx = (w[0] + 0.5) - cx, wdz = (w[2] + 0.5) - cz;
                if (Math.sqrt(wdx * wdx + wdz * wdz) < 0.8) wpIdx++;
                else break;
            }
            wpLeft = Math.max(0, path.size() - wpIdx);
            if (wpIdx >= path.size()) {
                if (Math.sqrt(dxG * dxG + dzG * dzG) <= tolerance + 2) succeed();
                else fail("waypoints_exhausted");
                restoreInput(client);
                return;
            }
            wp = path.get(wpIdx);
        }

        double wpDx = (wp[0] + 0.5) - cx, wpDz = (wp[2] + 0.5) - cz;

        // Stuck detection
        if (!Double.isNaN(lastX)) {
            double moved = Math.sqrt((cx - lastX) * (cx - lastX) + (cz - lastZ) * (cz - lastZ));
            if (moved > 0.3) {
                lastX = cx; lastZ = cz;
                lastMoveMs = System.currentTimeMillis();
                jumpPending = false;
            } else if (System.currentTimeMillis() - lastMoveMs > 3000) {
                if (!jumpPending) { jumpPending = true; lastMoveMs = System.currentTimeMillis(); }
                else if (System.currentTimeMillis() - lastMoveMs > 4000) {
                    fail("stuck_no_movement"); restoreInput(client); return;
                }
            }
        } else {
            lastX = cx; lastZ = cz;
            lastMoveMs = System.currentTimeMillis();
        }

        float targetYaw = (float) Math.toDegrees(Math.atan2(-wpDx, wpDz));
        player.setYaw(targetYaw);
        player.setPitch(0f);

        boolean needsJump = (wp[1] > (int) Math.floor(cy)) && player.isOnGround();
        NavigatorInput ni = getNavInput(client);
        if (ni != null) { ni.navForward = 1.0f; ni.navJump = needsJump || jumpPending; ni.overriding = true; }
        player.setSprinting(true);
    }

    // ── Input management ──────────────────────────────────────────────────────

    private void installInput(MinecraftClient client) {
        if (client.player == null) return;
        if (!(client.player.input instanceof NavigatorInput))
            client.player.input = new NavigatorInput(client.player.input);
        ((NavigatorInput) client.player.input).overriding = true;
    }

    private void restoreInput(MinecraftClient client) {
        if (client.player == null) return;
        if (client.player.input instanceof NavigatorInput ni) {
            ni.overriding = false; ni.navForward = 0f; ni.navJump = false;
            client.player.input = ni.getOriginal();
        }
    }

    private NavigatorInput getNavInput(MinecraftClient client) {
        if (client.player != null && client.player.input instanceof NavigatorInput ni) return ni;
        client.execute(() -> installInput(client));
        return null;
    }

    private void succeed() { synchronized (lock) { status = NavStatus.DONE; failReason = ""; } }
    private void fail(String r) { synchronized (lock) { status = NavStatus.FAILED; failReason = r; } }

    // ── A* Pathfinder ─────────────────────────────────────────────────────────

    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final double COST_STRAIGHT = 1.00, COST_DIAGONAL = 1.4142135, COST_JUMP = 2.0;
    private static final double[] COST_FALL   = {0.0, 0.5, 0.8, 1.2};

    private boolean isSolid(MinecraftClient c, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState bs = c.world.getBlockState(pos);
        return !bs.isAir() && !bs.getCollisionShape(c.world, pos).isEmpty();
    }

    private boolean canStand(MinecraftClient c, int x, int y, int z) {
        return isSolid(c, x, y - 1, z) && !isSolid(c, x, y, z) && !isSolid(c, x, y + 1, z);
    }

    // Bit layout (62 bits total, sign bit always 0):
    //   bits  0-25  z + 30_000_000  (26 bits, covers ±30M)
    //   bits 26-35  y + 64          (10 bits, covers -64 to +959)
    //   bits 36-61  x + 30_000_000  (26 bits, covers ±30M)
    private static long enc(int x, int y, int z) {
        return ((long)(x + 30_000_000) << 36) | ((long)(y + 64) << 26) | (long)(z + 30_000_000);
    }
    private static int decX(long k) { return (int)(k >> 36) - 30_000_000; }
    private static int decY(long k) { return (int)((k >> 26) & 0x3FF) - 64; }
    private static int decZ(long k) { return (int)(k & 0x3FFFFFF) - 30_000_000; }

    private List<int[]> astar(MinecraftClient c, int sx, int sy, int sz, int gx, int gy, int gz, int maxNodes) {
        outer: for (int dy = 0; dy <= 3; dy++) for (int s : new int[]{0, dy, -dy})
            if (canStand(c, sx, sy + s, sz)) { sy += s; break outer; }
        outer2: for (int dy = 0; dy <= 4; dy++) for (int s : new int[]{0, dy, -dy})
            if (canStand(c, gx, gy + s, gz)) { gy += s; break outer2; }

        long startKey = enc(sx, sy, sz), goalKey = enc(gx, gy, gz);
        if (startKey == goalKey) return new ArrayList<>();

        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
        Map<Long, Long>   cameFrom = new HashMap<>();
        Map<Long, Double> gScore   = new HashMap<>();

        double h0 = heuristic(sx, sy, sz, gx, gy, gz);
        open.add(new long[]{(long)(h0 * 1000), startKey, 0L, sx, sy, sz});
        gScore.put(startKey, 0.0);

        int explored = 0;
        while (!open.isEmpty() && explored < maxNodes) {
            long[] cur   = open.poll();
            long   curKey = cur[1];
            int    cx    = (int) cur[3], cy = (int) cur[4], cz = (int) cur[5];
            double curG  = cur[2] / 1000.0;
            explored++;

            if (curG > gScore.getOrDefault(curKey, Double.MAX_VALUE) + 0.001) continue;
            if (Math.abs(cx - gx) <= 1 && Math.abs(cz - gz) <= 1)
                return reconstruct(cameFrom, curKey, startKey);

            for (int[] dir : DIRS) {
                int dx = dir[0], dz = dir[1];
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal) {
                    if (isSolid(c, cx + dx, cy, cz) || isSolid(c, cx, cy, cz + dz)) continue;
                    if (isSolid(c, cx + dx, cy + 1, cz) || isSolid(c, cx, cy + 1, cz + dz)) continue;
                }
                double baseCost = diagonal ? COST_DIAGONAL : COST_STRAIGHT;
                for (int dy = 1; dy >= -3; dy--) {
                    int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                    if (!canStand(c, nx, ny, nz)) continue;
                    double moveCost = baseCost;
                    if (dy == 1) {
                        if (isSolid(c, cx, cy + 2, cz)) continue;
                        moveCost += COST_JUMP;
                    } else if (dy < 0) {
                        boolean clear = true;
                        for (int fy = cy - 1; fy >= ny + 1; fy--)
                            if (isSolid(c, nx, fy, nz)) { clear = false; break; }
                        if (!clear) continue;
                        moveCost += COST_FALL[-dy];
                    }
                    long nKey = enc(nx, ny, nz);
                    double ng = curG + moveCost;
                    if (ng < gScore.getOrDefault(nKey, Double.MAX_VALUE)) {
                        gScore.put(nKey, ng);
                        cameFrom.put(nKey, curKey);
                        double h = heuristic(nx, ny, nz, gx, gy, gz);
                        open.add(new long[]{(long)((ng + h) * 1000), nKey, (long)(ng * 1000), nx, ny, nz});
                    }
                    break;
                }
            }
        }
        return null;
    }

    private List<int[]> reconstruct(Map<Long, Long> cameFrom, long endKey, long startKey) {
        LinkedList<int[]> result = new LinkedList<>();
        long key = endKey;
        while (key != startKey) {
            result.addFirst(new int[]{decX(key), decY(key), decZ(key)});
            Long parent = cameFrom.get(key);
            if (parent == null) break;
            key = parent;
        }
        result.addFirst(new int[]{decX(startKey), decY(startKey), decZ(startKey)});
        return new ArrayList<>(result);
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        double dx = Math.abs(x - gx), dy = Math.abs(y - gy), dz = Math.abs(z - gz);
        double xz = dx > dz ? dx * COST_STRAIGHT + dz * (COST_DIAGONAL - COST_STRAIGHT)
                             : dz * COST_STRAIGHT + dx * (COST_DIAGONAL - COST_STRAIGHT);
        return xz + dy * COST_JUMP;
    }
}
