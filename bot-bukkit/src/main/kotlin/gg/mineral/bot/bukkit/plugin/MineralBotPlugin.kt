package gg.mineral.bot.bukkit.plugin

import com.github.retrooper.packetevents.PacketEvents
import gg.mineral.bot.api.BotAPI
import gg.mineral.bot.base.client.manager.InstanceManager
import gg.mineral.bot.base.client.tick.GameLoop
import gg.mineral.bot.bukkit.plugin.command.MineralBotCommand
import gg.mineral.bot.bukkit.plugin.impl.ServerBotImpl
import gg.mineral.bot.impl.thread.ThreadManager.shutdown
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import net.minecraft.client.Minecraft
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin


class MineralBotPlugin : JavaPlugin() {
    companion object {
        lateinit var instance: MineralBotPlugin
    }

    // True when this plugin owns the bundled PacketEvents lifecycle (standalone plugin absent).
    private var ownsPacketEvents = false

    override fun onLoad() {
        // packetevents is bundled but only self-initialized when the standalone plugin isn't
        // installed — softdepend ordering guarantees an installed plugin has already setAPI here.
        if (PacketEvents.getAPI() == null) {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
            PacketEvents.getAPI().load()
            ownsPacketEvents = true
        }
        System.setProperty("java.net.preferIPv4Stack", "true")
        ServerBotImpl.init()
        Minecraft.init()
    }

    override fun onEnable() {
        instance = this
        if (ownsPacketEvents) PacketEvents.getAPI().init()
        getCommand("mineralbot").executor = MineralBotCommand()
        GameLoop.start()
    }

    override fun onDisable() {
        BotAPI.INSTANCE.despawnAll()
        logger.info("Disabling MineralBotPlugin...")

        // Cancel all tasks to prevent memory leaks
        Bukkit.getScheduler().cancelTasks(this)

        // Ensure all instances are removed
        InstanceManager.instances.clear()
        InstanceManager.pendingInstances.clear()

        shutdown()

        ServerBotImpl.destroy()

        if (ownsPacketEvents) PacketEvents.getAPI().terminate()
    }
}
