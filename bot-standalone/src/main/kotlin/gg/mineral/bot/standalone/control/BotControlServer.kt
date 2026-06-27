package gg.mineral.bot.standalone.control

import com.google.common.collect.HashMultimap
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import gg.mineral.bot.ai.goal.DrinkPotionGoal
import gg.mineral.bot.ai.goal.DropEmptyBowlGoal
import gg.mineral.bot.ai.goal.EatFoodGoal
import gg.mineral.bot.ai.goal.EatGappleGoal
import gg.mineral.bot.ai.goal.HealSoupGoal
import gg.mineral.bot.ai.goal.MeleeCombatGoal
import gg.mineral.bot.ai.goal.ReplaceArmorGoal
import gg.mineral.bot.ai.goal.ThrowHealthPotGoal
import gg.mineral.bot.ai.goal.ThrowPearlGoal
import gg.mineral.bot.ai.goal.TntTagGoal
import gg.mineral.bot.api.configuration.BotConfiguration
import gg.mineral.bot.api.goal.Goal
import gg.mineral.bot.base.client.instance.ClientInstance
import gg.mineral.bot.base.client.manager.InstanceManager
import gg.mineral.bot.impl.thread.ThreadManager
import org.apache.logging.log4j.LogManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The remote-control surface of the off-box bot host. Moon (on the game server) opens a TCP socket to
 * this port and drives bots with newline-delimited JSON: `connect` brings a real 1.7.10 client online
 * against the game server (real network latency - NO simulation; [BotConfiguration.latency] stays 0),
 * `disconnect` takes it back down. Each bot runs its own blocking client loop on [ThreadManager]; goals
 * are attached once the bot is actually in-world.
 *
 * Protocol (one JSON object per line):
 *   {"cmd":"connect","name":"Huahwi","host":"1.2.3.4","port":25565,"mode":"BOXING",
 *    "friendly":["<uuid>",...], "averageCps":8.0, "horizontalAimAccuracy":0.8, ...}
 *   {"cmd":"disconnect","name":"Huahwi"}
 *   {"cmd":"list"}   {"cmd":"ping"}
 * Replies are one JSON object per line: {"ok":true,...} / {"ok":false,"error":"..."}.
 */
class BotControlServer(private val port: Int, private val runDir: File) {

    private val gson = Gson()
    private val instances = ConcurrentHashMap<String, ClientInstance>()

    fun start() {
        Thread({ acceptLoop() }, "BotControl-Accept").apply { isDaemon = true }.start()
        logger.info("Bot control server listening on port {}", port)
    }

    private fun acceptLoop() {
        ServerSocket(port).use { server ->
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: Throwable) {
                    logger.warn("accept failed", e); continue
                }
                Thread({ handleClient(socket) }, "BotControl-Client").apply { isDaemon = true }.start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        logger.info("Control client connected: {}", socket.remoteSocketAddress)
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), true)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val reply = try {
                    dispatch(JsonParser.parseString(line).asJsonObject)
                } catch (e: Throwable) {
                    logger.warn("command failed: {}", line, e)
                    err(e.message ?: e.javaClass.simpleName)
                }
                writer.println(gson.toJson(reply))
            }
        } catch (e: Throwable) {
            logger.warn("control client error", e)
        } finally {
            try { socket.close() } catch (ignored: Throwable) {}
            logger.info("Control client disconnected")
        }
    }

    private fun dispatch(o: JsonObject): JsonObject {
        return when (o.get("cmd")?.asString) {
            "ping" -> ok()
            "list" -> { prune(); ok().apply { addProperty("count", instances.size); addProperty("names", instances.keys.joinToString(",")) } }
            "connect" -> connect(o)
            "disconnect" -> disconnect(o.get("name").asString)
            "info" -> info(o.get("name").asString)
            else -> err("unknown cmd")
        }
    }

    /** Diagnostics: what does this bot actually see? (world entities, players, nearest player). */
    private fun info(name: String): JsonObject {
        val instance = instances[name] ?: return err("not connected: $name")
        return try {
            val fp = instance.fakePlayer
            val world = fp.world
            val entities = world.entities
            val players = entities.filterIsInstance<gg.mineral.bot.api.entity.living.player.ClientPlayer>()
            val nearest = players.minByOrNull { fp.distance3DTo(it) }
            ok().apply {
                addProperty("running", instance.isRunning)
                addProperty("x", fp.x); addProperty("y", fp.y); addProperty("z", fp.z)
                addProperty("entityCount", entities.size)
                addProperty("playerCount", players.size)
                addProperty("players", players.joinToString(",") { "${it.uuid}@${"%.1f".format(fp.distance3DTo(it))}" })
                addProperty("friendlies", instance.configuration.friendlyUUIDs.joinToString(","))
                if (nearest != null) addProperty("nearestDist", fp.distance3DTo(nearest).toDouble())
            }
        } catch (e: Throwable) {
            err("info failed: ${e.message}")
        }
    }

    /** Drop entries whose client has stopped running (disconnected), so names can be reused. */
    private fun prune() = instances.entries.removeIf { !it.value.isRunning }

    private fun connect(o: JsonObject): JsonObject {
        prune()
        val name = o.get("name").asString
        if (instances.containsKey(name)) return err("already connected: $name")
        val host = o.get("host").asString
        val port = o.get("port")?.asInt ?: 25565
        val mode = (o.get("mode")?.asString ?: "BOXING").uppercase()

        val friendly = HashSet<UUID>()
        o.getAsJsonArray("friendly")?.forEach { runCatching { friendly.add(UUID.fromString(it.asString)) } }

        fun f(key: String, def: Float) = o.get(key)?.asFloat ?: def
        fun i(key: String, def: Int) = o.get(key)?.asInt ?: def

        // Real network latency only - latency/latencyDeviation are deliberately 0 (no simulation).
        val cfg = BotConfiguration(
            username = name,
            uuid = offlineUuid(name),
            runDirectory = runDir,
            averageCps = f("averageCps", 8.0f),
            cpsDeviation = f("cpsDeviation", 1.0f),
            targetSearchRange = i("targetSearchRange", 96),
            horizontalAimSpeed = f("horizontalAimSpeed", 0.5f),
            verticalAimSpeed = f("verticalAimSpeed", 0.5f),
            horizontalAimAccuracy = f("horizontalAimAccuracy", 0.8f),
            verticalAimAccuracy = f("verticalAimAccuracy", 0.72f),
            horizontalErraticness = f("horizontalErraticness", 0.2f),
            verticalErraticness = f("verticalErraticness", 0.2f),
            reach = f("reach", 3.0f),
            sprintResetAccuracy = f("sprintResetAccuracy", 0.7f),
            hitSelectAccuracy = f("hitSelectAccuracy", 0.8f),
            latency = 0,
            latencyDeviation = 0,
            disableEntityCollisions = true,
            friendlyUUIDs = friendly
        )

        val instance = ClientInstance(
            cfg, 1280, 720, false, false,
            runDir, File(runDir, "assets"), File(runDir, "resourcepacks"),
            Proxy.NO_PROXY, "Mineral-Bot-Client", HashMultimap.create<Any?, Any?>(), "1.7.10"
        )
        instance.setServer(host, port)
        instances[name] = instance

        // Watch for the bot reaching the world, then attach its goals. MUST be its own thread (it
        // sleeps) - never the shared ForkJoinPool, or it occupies a pool worker the client loop needs.
        // mode=NONE = connect only (no AI), used to isolate connection issues from goal behaviour.
        if (mode != "NONE") {
            Thread({ attachGoalsWhenReady(name, instance, mode) }, "Bot-Goals-$name").apply { isDaemon = true }.start()
        }

        // Connect on a dedicated thread. run() performs the handshake/join and RETURNS (it is not the
        // long-lived loop) - GameLoop ticks every instance in InstanceManager.instances, so we must hand
        // the live instance to it after run() returns. (GameLoop's cleanup loop later evicts it from
        // `instances` when !running, i.e. on disconnect.) Don't run() on the shared ForkJoinPool: blocking
        // a pool worker during connect starves the loop and races the LOGIN->PLAY handler swap.
        Thread({
            InstanceManager.pendingInstances[cfg.uuid] = instance
            try {
                instance.run()
                InstanceManager.pendingInstances.remove(cfg.uuid)
                InstanceManager.instances[cfg.uuid] = instance
                logger.info("bot {} connected (ticking via GameLoop)", name)
            } catch (e: Throwable) {
                logger.warn("bot {} connect failed", name, e)
                InstanceManager.pendingInstances.remove(cfg.uuid)
                instances.remove(name, instance)
            }
        }, "Bot-Connect-$name").apply { isDaemon = true }.start()
        logger.info("connecting bot {} -> {}:{} mode={} friendly={}", name, host, port, mode, friendly.size)
        return ok().apply { addProperty("name", name); addProperty("uuid", offlineUuid(name).toString()) }
    }

    private fun attachGoalsWhenReady(name: String, instance: ClientInstance, mode: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (instances[name] !== instance) return // disconnected while waiting
            val ready = try {
                instance.fakePlayer.let { it != null && it.world != null }
            } catch (e: Throwable) {
                false
            }
            if (ready) {
                try {
                    instance.startGoals(*goalsFor(mode, instance))
                    logger.info("bot {} goals started ({})", name, mode)
                } catch (e: Throwable) {
                    logger.warn("bot {} startGoals failed", name, e)
                }
                return
            }
            try { Thread.sleep(200) } catch (e: InterruptedException) { return }
        }
        logger.warn("bot {} never reached world within 30s; goals not started", name)
    }

    private fun goalsFor(mode: String, i: ClientInstance): Array<Goal> = when (mode) {
        "CLASSIC" -> arrayOf(
            ReplaceArmorGoal(i), HealSoupGoal(i), EatGappleGoal(i), DropEmptyBowlGoal(i), MeleeCombatGoal(i)
        )
        "NODEBUFF" -> arrayOf(
            ReplaceArmorGoal(i), DrinkPotionGoal(i), ThrowHealthPotGoal(i), ThrowPearlGoal(i),
            EatFoodGoal(i), MeleeCombatGoal(i)
        )
        "TNTTAG" -> arrayOf(TntTagGoal(i))
        else -> arrayOf(MeleeCombatGoal(i)) // BOXING / SUMO / default
    }

    private fun disconnect(name: String): JsonObject {
        val instance = instances.remove(name) ?: return err("not connected: $name")
        try {
            instance.shutdownMinecraftApplet()
        } catch (e: Throwable) {
            logger.warn("disconnect {} failed", name, e)
        }
        logger.info("disconnect bot {}", name)
        return ok().apply { addProperty("name", name) }
    }

    private fun ok() = JsonObject().apply { addProperty("ok", true) }
    private fun err(msg: String) = JsonObject().apply { addProperty("ok", false); addProperty("error", msg) }

    companion object {
        private val logger = LogManager.getLogger(BotControlServer::class.java)

        /** The offline-mode UUID the game server will assign this username (matches Moon's lookup). */
        fun offlineUuid(name: String): UUID =
            UUID.nameUUIDFromBytes(("OfflinePlayer:$name").toByteArray(Charsets.UTF_8))
    }
}
