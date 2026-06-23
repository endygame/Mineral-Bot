package gg.mineral.bot.ai.goal

import gg.mineral.bot.ai.goal.type.InventoryGoal
import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.MouseButton
import gg.mineral.bot.api.entity.living.player.ClientPlayer
import gg.mineral.bot.api.event.Event
import gg.mineral.bot.api.instance.ClientInstance
import gg.mineral.bot.api.inv.item.Item
import gg.mineral.bot.api.screen.type.ContainerScreen
import gg.mineral.bot.api.world.block.Block

/**
 * The whole brain for a bot playing Moon's **TNT Tag** event.
 *
 * Unlike a duel, the bot has no out-of-band "who is IT" signal — it reads the game state purely from
 * what it can see, exactly like a human: a tagger holds a TNT block in hand and wears a TNT helmet
 * ([Block.TNT] = 46). So:
 *
 *  * **If I am the tagger** (TNT in my hand/helmet) → hunt the nearest player: sprint at them, aim,
 *    melee. If they are far, **pearl onto them** to close the gap and surprise-tag.
 *  * **If I am NOT the tagger** → find the player who *is* IT (TNT helmet) and run the opposite way.
 *    When that tagger gets dangerously close, **pearl away** to escape.
 *
 * The pearl is only thrown at the *situationally correct* moment — to close a real gap when hunting,
 * or to break contact when a tagger is about to tag me — and never more often than the configured
 * [gg.mineral.bot.api.configuration.BotConfiguration.pearlCooldown] allows.
 */
class TntTagGoal(clientInstance: ClientInstance) : InventoryGoal(clientInstance) {

    private var nextClick = 0L
    private val meanDelay = (1000 / clientInstance.configuration.averageCps).toLong()
    private var lastPearledTick = 0
    private var target: ClientPlayer? = null

    private var wasTagger = false
    private var becameTaggerTick = 0
    // Brief human-like reaction window after grabbing the TNT before swinging, so the bot can't
    // hot-potato it off instantly — it must commit to the chase, giving the fuse a chance to run out.
    private val reactionTicks = 10 // ~0.5s

    override fun shouldExecute() = true

    private fun isTnt(stack: gg.mineral.bot.api.inv.item.ItemStack?): Boolean =
        stack != null && stack.item.id == Block.TNT

    /** A tagger holds TNT in hand or wears the TNT helmet. */
    private fun isTagger(player: ClientPlayer): Boolean {
        val inv = player.inventory
        return isTnt(inv.heldItemStack) || isTnt(inv.helmet)
    }

    private fun amTagger(): Boolean = isTagger(clientInstance.fakePlayer)

    private fun enemies(): List<ClientPlayer> {
        val friendly = clientInstance.configuration.friendlyUUIDs
        return clientInstance.fakePlayer.world.entities
            .filterIsInstance<ClientPlayer>()
            .filter { !friendly.contains(it.uuid) }
    }

    override fun onTick(tick: Tick) {
        val fakePlayer = clientInstance.fakePlayer
        val others = enemies()
        if (others.isEmpty()) {
            unpressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL, Key.Type.KEY_S, Key.Type.KEY_SPACE)
            target = null
            return
        }

        val hunting = amTagger()
        if (hunting && !wasTagger) becameTaggerTick = clientInstance.currentTick
        wasTagger = hunting

        val focus: ClientPlayer = if (hunting) {
            others.minByOrNull { fakePlayer.distance3DTo(it) }!!
        } else {
            // Prefer the actual IT player; if none visible, treat the nearest as the threat.
            (others.filter { isTagger(it) }.minByOrNull { fakePlayer.distance3DTo(it) }
                ?: others.minByOrNull { fakePlayer.distance3DTo(it) }!!)
        }
        target = focus

        // Always run forward + sprint; the aim below decides which way "forward" points.
        pressKey(Key.Type.KEY_W, Key.Type.KEY_LCONTROL)
        unpressKey(Key.Type.KEY_S)

        // Light terrain navigation: hop up steps / over obstacles in the travel direction.
        navigate()

        // Try the situational pearl first; if it handled this tick, don't fight the aim.
        if (tryPearl(focus, offensive = hunting)) return

        if (hunting) {
            val angles = computeOptimalYawAndPitch(fakePlayer, focus)
            setMousePitch(angles[0])
            setMouseYaw(angles[1])
        } else {
            // Face directly away from the tagger so W carries us off.
            setMouseYaw(computeOptimalYaw(fakePlayer, focus) + 180f)
            setMousePitch(0f)
        }
    }

    /**
     * Throw a pearl iff the moment is right:
     *  * hunting: target is 7–28 blocks away (close the gap), or
     *  * fleeing: the tagger is within 5.5 blocks (break contact).
     *
     * @return true if this tick was spent setting up / throwing the pearl.
     */
    private fun tryPearl(other: ClientPlayer, offensive: Boolean): Boolean {
        val cfg = clientInstance.configuration
        if (clientInstance.currentTick - lastPearledTick < 20 * cfg.pearlCooldown) return false

        val fakePlayer = clientInstance.fakePlayer
        val inventory = fakePlayer.inventory
        if (!inventory.contains(Item.ENDER_PEARL)) return false

        val dist = fakePlayer.distance3DTo(other)
        val want = if (offensive) dist in 7.0..28.0 else dist <= 5.5
        if (!want) return false

        val slot = pearlSlot()
        if (slot == -1) return false

        // Aim every tick while we line the throw up so rotation has settled by release.
        if (offensive) aimLead(other) else aimAway(other)

        if (clientInstance.currentScreen is ContainerScreen) {
            pressKey(10, Key.Type.KEY_ESCAPE); return true
        }
        if (slot > 8) {
            moveItemToHotbar(slot, inventory); return true
        }
        if (inventory.heldSlot != slot) {
            pressKey(10, Key.Type.valueOf("KEY_" + (slot + 1))); return true
        }
        if (inventory.heldItemStack?.item?.id != Item.ENDER_PEARL) return true

        lastPearledTick = clientInstance.currentTick
        pressButton(10, MouseButton.Type.RIGHT_CLICK)
        return true
    }

    private fun pearlSlot(): Int {
        val inventory = clientInstance.fakePlayer.inventory
        for (i in 0..35) {
            if (inventory.getItemStackAt(i)?.item?.id == Item.ENDER_PEARL) return i
        }
        return -1
    }

    /** Lead the target a touch and arc the pearl onto them. */
    private fun aimLead(entity: ClientPlayer) {
        val fakePlayer = clientInstance.fakePlayer
        val lead = if (entity.isSprinting) 0.5 else 0.35
        val x = (entity.x + (entity.x - entity.lastX) * lead) - fakePlayer.x
        val z = (entity.z + (entity.z - entity.lastZ) * lead) - fakePlayer.z
        val y = (fakePlayer.y + fakePlayer.eyeHeight) - (entity.y + entity.eyeHeight)
        val flat = Math.sqrt(x * x + z * z)
        val yaw = (Math.toDegrees(Math.atan2(z, x)) - 90.0).toFloat()
        val pitch = -(Math.toDegrees(Math.atan2(y, flat))).toFloat() + flat.toFloat() * 0.11f
        setMouseYaw(yaw)
        setMousePitch(-pitch)
    }

    /** Throw the pearl up-and-away from the threat to break contact. */
    private fun aimAway(threat: ClientPlayer) {
        setMouseYaw(computeOptimalYaw(clientInstance.fakePlayer, threat) + 180f)
        setMousePitch(-32f)
    }

    /**
     * Cheap, combat-friendly pathing. Looks one block ahead in the travel direction; if a solid block
     * is at body height but clear above it, jump to step up (stairs, ledges, walls). Also jumps when
     * boxed in on the ground so the bot doesn't stall against geometry. Only presses SPACE when actually
     * blocked, so it never disturbs strafing/sprint-reset timing on flat ground.
     */
    private fun navigate() {
        val fakePlayer = clientInstance.fakePlayer
        if (!fakePlayer.isOnGround) { unpressKey(Key.Type.KEY_SPACE); return }

        val world = fakePlayer.world
        val dir = vectorForRotation(0f, fakePlayer.yaw)
        val aheadX = fakePlayer.x + dir[0] * 0.6
        val aheadZ = fakePlayer.z + dir[2] * 0.6

        val atFeet = world.getBlockAt(aheadX, fakePlayer.y, aheadZ).id != Block.AIR
        val atKnee = world.getBlockAt(aheadX, fakePlayer.y + 0.5, aheadZ).id != Block.AIR
        val atHead = world.getBlockAt(aheadX, fakePlayer.y + 1.2, aheadZ).id != Block.AIR

        // Step up a 1-block obstacle (solid low, open above), or hop when wedged against a wall.
        if ((atFeet || atKnee) && !atHead) {
            pressKey(Key.Type.KEY_SPACE)
        } else {
            unpressKey(Key.Type.KEY_SPACE)
        }
    }

    override fun onEvent(event: Event): Boolean = false

    override fun onGameLoop() {
        if (!amTagger()) {
            unpressButton(MouseButton.Type.LEFT_CLICK)
            return
        }
        val t = target ?: return
        // Hold fire briefly after first grabbing the TNT — no instant hot-potato.
        if (clientInstance.currentTick - becameTaggerTick < reactionTicks) return
        val fakePlayer = clientInstance.fakePlayer
        if (fakePlayer.distance3DTo(t) > clientInstance.configuration.reach + 0.3f) return
        if (timeMillis() >= nextClick) {
            nextClick = (timeMillis() + fakePlayer.random.nextGaussian(meanDelay.toDouble(), 20.0)).toLong()
            pressButton(25, MouseButton.Type.LEFT_CLICK)
        }
    }
}
