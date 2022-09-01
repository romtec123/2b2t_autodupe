import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.modules.player.PacketCancel
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.getHitVec
import net.minecraft.block.BlockButton
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.entity.Entity
import net.minecraft.entity.item.EntityBoat
import net.minecraft.entity.item.EntityMinecart
import net.minecraft.entity.passive.EntityDonkey
import net.minecraft.init.Blocks
import net.minecraft.init.SoundEvents
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.text.TextComponentString
import net.minecraftforge.fml.common.gameevent.TickEvent

internal object AutoDupe : PluginModule(
    name = "AutoDupe",
    category = Category.MISC,
    description = "$$$$$$$$$",
    pluginMain = DoopMain
) {
    private val page = setting("Page", Page.GENERAL)
    var startMinecartX by setting("StartMinecartX", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var startMinecartY by setting("StartMinecartY", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var startMinecartZ by setting("StartMinecartZ", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var returnMinecartX by setting("ReturnMinecartX", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var returnMinecartY by setting("ReturnMinecartY", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var returnMinecartZ by setting("ReturnMinecartZ", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var gasButtonX by setting("GasButtonX", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var gasButtonY by setting("GasButtonY", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var gasButtonZ by setting("GasButtonZ", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var resetButtonX by setting("ResetButtonX", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var resetButtonY by setting("ResetButtonY", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var resetButtonZ by setting("ResetButtonZ", 0, Int.MIN_VALUE..Int.MAX_VALUE, 1, page.atValue(Page.COORDS))
    var debug by setting("Debug", false, page.atValue(Page.GENERAL))
    private val waitSeconds by setting("Unload Wait Time", 6, 0..15, 1, page.atValue(Page.GENERAL))
    private val killWaitSeconds by setting("Kill Wait Time", 10, 0..30, 1, page.atValue(Page.GENERAL))
    private val mountDelay by setting("Minecart Mount Tick Delay", 5, 0..20, 1, page.atValue(Page.GENERAL))
    private val dismountDelay by setting("Donkey Dismount Tick Delay", 30, 0..60, 1, page.atValue(Page.GENERAL))
    private val mountRange by setting("Mount Range", 6.0f, 1.0f..10.0f, 0.5f, page.atValue(Page.GENERAL))
    private val nextDupeSeconds by setting("Next Dupe Wait", 200, 0..300, 5, page.atValue(Page.GENERAL))
    private var totalDupes = 0
    private var lastStep = -1
    private var step = 0
    private var failed = false
    private var previousCartid = 0
    private var mountTimer = TickTimer(TimeUnit.TICKS)
    private var dismountTimer = TickTimer(TimeUnit.TICKS)
    private var packetCancelEnableTime: Long = 0
    private var mountList: MutableList<Int> = ArrayList()
    private var currentMinecart = 0
    private var gasWait = false
    private var dupeTime = 0L
    private var startNextDupeTime = 0L
    private var resetButtonPressed = false
    private var gassed = false
    private var dupeFinished = false

    private enum class Page {
        GENERAL, COORDS
    }

    override fun getHudInfo(): String {
        return "Step: ${step} Duped: ${totalDupes}"
    }

    init{
        onEnable {
            gassed = false
            totalDupes = 0
            failed = false
            step = 0
            PacketCancel.disable()
            previousCartid = 0
            mountTimer.reset()
            mountList.clear()
            gasWait = false
            dupeTime = 0L
            resetButtonPressed = false
            startNextDupeTime = 0L
            dupeFinished = false
        }

        onDisable {
            PacketCancel.disable()
            if(totalDupes > 0) sendChatMessage("Successfully duped ${totalDupes} donkeys.")
        }

        safeListener<TickEvent.ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.START) return@safeListener
            val firstCartPos = BlockPos(startMinecartX, startMinecartY, startMinecartZ)
            val lastCartPos = BlockPos(returnMinecartX, returnMinecartY, returnMinecartZ)
            if(lastStep != step){
                lastStep = step
                if(debug) sendChatMessage("Doing step ${step}.")
            }
            when(step) {
                0 -> {
                    if(dupeTime == 0L) dupeTime = System.currentTimeMillis()
                    if (mountTimer.tick(mountDelay)) {
                        if(player.isRiding && player.ridingEntity is EntityMinecart) currentMinecart = player.ridingEntity?.entityId ?: currentMinecart
                        mountTimer.reset()
                        for (entity in world.loadedEntityList) {
                            if(entity is EntityMinecart && player.getDistance(entity) <= mountRange){
                                if(player.getDistanceSq(lastCartPos) <= 1.0){
                                    step = 1
                                    mountList.clear()
                                    currentMinecart = 0
                                    return@safeListener
                                }
                                if(player.isRiding && !mountList.contains(currentMinecart)){
                                    mountList.add(currentMinecart)
                                }
                                if(!mountList.contains(entity.entityId)) mountEntity(entity)
                            }
                        }
                    }
                }
                1 -> {
                    //enable packet cancel
                    PacketCancel.enable()
                    step = 2
                    if(debug) sendChatMessage("Packet Canceler Enabled.")
                    mountTimer.reset()
                    return@safeListener
                }
                2 -> {
                    //head back
                    if (mountTimer.tick(mountDelay)) {
                        if(player.isRiding && player.ridingEntity is EntityMinecart) currentMinecart = player.ridingEntity?.entityId ?: currentMinecart
                        mountTimer.reset()
                        for (entity in world.loadedEntityList) {
                            if(entity is EntityMinecart && player.getDistance(entity) <= mountRange){
                                if(player.getDistanceSq(firstCartPos) <= 1.0){
                                    step = 3
                                    mountList.clear()
                                    currentMinecart = 0
                                    return@safeListener
                                }
                                if(player.isRiding && !mountList.contains(currentMinecart)){
                                    mountList.add(currentMinecart)
                                }
                                if(!mountList.contains(entity.entityId)) mountEntity(entity)
                            }
                        }
                    }
                }
                3 -> {
                    //Find non-chested donkey and mount
                    if(player.isRiding && player.ridingEntity is EntityDonkey){
                        step = 4
                        dismountTimer.reset()
                        return@safeListener
                    }
                    if (mountTimer.tick(mountDelay)) {
                        mountTimer.reset()
                        world.loadedEntityList.filterIsInstance<EntityDonkey>().minByOrNull {
                            player.getDistance(it)
                        }?.let {
                            mountEntity(it)
                        }
                    }
                }
                4 -> {
                    //Remount back to minecart
                    if(player.isRiding && player.ridingEntity is EntityMinecart){
                        step = 5
                        packetCancelEnableTime = System.currentTimeMillis()
                    }
                    if (dismountTimer.tick(dismountDelay)) {
                        dismountTimer.reset()
                        world.loadedEntityList.filterIsInstance<EntityMinecart>().minByOrNull {
                            player.getDistance(it)
                        }?.let {
                            mountEntity(it)
                        }
                    }
                }
                5 -> {
                    //Wait, then disable packet cancel
                    if(debug) sendChatMessage("Waiting. ${System.currentTimeMillis() - packetCancelEnableTime} > ${waitSeconds * 1000}")
                    if(System.currentTimeMillis() - packetCancelEnableTime >= waitSeconds * 1000 && packetCancelEnableTime != 0L){
                        packetCancelEnableTime = 0
                        PacketCancel.disable()
                        step = 6
                        mountTimer.reset()
                    }
                }
                6 -> {
                    //Mount chested boat donkey
                    if (mountTimer.tick(mountDelay)) {
                        mountTimer.reset()
                        if(player.isRiding && player.ridingEntity is EntityDonkey && (player.ridingEntity as EntityDonkey).hasChest()){
                            step = 7
                            return@safeListener
                        }
                        for (entity in world.loadedEntityList) {
                            if (entity is EntityDonkey && player.getDistance(entity) <= mountRange) {
                                if (world.loadedEntityList.filterIsInstance<EntityDonkey>().filter { player.getDistance(entity) <= mountRange }.size == 1) {
                                    failed = true
                                    sendChatMessage("Dupe failed; only 1 donkey seen. (Step 6)")
                                    step = 7
                                    return@safeListener
                                }
                                if (player.isRiding && player.ridingEntity is EntityMinecart && entity.hasChest() && entity.ridingEntity is EntityBoat) {
                                    mountEntity(entity)
                                }
                            }
                        }
                    }
                }
                7 -> {
                    if (mountTimer.tick(mountDelay)) {
                        mountTimer.reset()
                        if(failed && player.isRiding && player.ridingEntity is EntityDonkey){
                            step = 9
                            return@safeListener
                        }
                        if(gassed && !failed && player.isRiding && player.ridingEntity is EntityDonkey && (player.ridingEntity as EntityDonkey).ridingEntity !is EntityBoat){
                            step = 8
                            return@safeListener
                        }

                        for (entity in world.loadedEntityList) {
                            if (entity is EntityDonkey && player.getDistance(entity) <= mountRange) {


                                if(!failed && entity.ridingEntity !is EntityBoat && gassed){
                                    mountEntity(entity)
                                }
                                //Other donkey shouldn't be in the boat, so fail if it is
                                if(!failed && player.ridingEntity != entity && entity.ridingEntity is EntityBoat ){
                                    failed = true
                                    sendChatMessage("Dupe failed; 2 donkeys in same boat (Step 7)")
                                    mountEntity(entity)
                                    return@safeListener
                                }

                                if (failed && entity.ridingEntity is EntityBoat) {
                                    mountEntity(entity)
                                }

                                if(!failed && entity.ridingEntity is EntityBoat && !gassed){
                                    mountTimer.reset()
                                    val btn = BlockPos(gasButtonX, gasButtonY, gasButtonZ)
                                    tryPressButton(btn)

                                }

                            }
                        }
                    }
                }
                8 -> {
                    //Wait after gassing
                    if(gasWait) {
                        if (debug) sendChatMessage("Waiting. ${System.currentTimeMillis() - packetCancelEnableTime} > ${killWaitSeconds * 1000}")
                        if (System.currentTimeMillis() - packetCancelEnableTime >= killWaitSeconds * 1000 && packetCancelEnableTime != 0L) {
                            packetCancelEnableTime = 0
                            gasWait = false
                            gassed = false
                            step = 9
                            mountTimer.reset()
                        }
                    }
                }
                9 -> {
                    //Disconnect
                    if (mountTimer.tick(mountDelay)) {
                        mountTimer.reset()
                        var reasonText = ""
                        if (failed) {
                            reasonText = "Dupe failed, relogging to restore donkeys."
                            mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                        } else {
                            reasonText = "Dupe completed, relogging."
                        }
                        connection.networkManager.closeChannel(TextComponentString(reasonText))
                        step = 10
                    }
                }
                10 -> {
                    //login, and remount minecart
                    if (mountTimer.tick(mountDelay)) {
                        mountTimer.reset()

                        if(player.isRiding && player.ridingEntity is EntityMinecart){
                            mountTimer.reset()
                            step = 11
                            val totalDupeTime = System.currentTimeMillis() - dupeTime
                            startNextDupeTime = System.currentTimeMillis() - totalDupeTime
                        }

                        if (player.isRiding && player.ridingEntity is EntityDonkey && !player.isSpectator) {
                            world.loadedEntityList.filterIsInstance<EntityMinecart>().minByOrNull {
                                player.getDistance(it)
                            }?.let {
                                mountEntity(it)

                            }
                        }
                    }
                }
                11 ->{
                    //Wait to start next dupe & press reset button
                    if(!dupeFinished){
                        dupeFinished = true
                        if(!failed) totalDupes++
                        sendChatMessage("Dupe completed! Total: ${totalDupes} Time: ${(System.currentTimeMillis() - dupeTime) / 1000} seconds")
                    }
                    if(!resetButtonPressed && mountTimer.tick(mountDelay)){
                        mountTimer.reset()
                        val btn = BlockPos(resetButtonX, resetButtonY, resetButtonZ)
                        tryPressButton(btn)
                    }
                    if(debug) sendChatMessage("Waiting. ${System.currentTimeMillis() - startNextDupeTime} > ${nextDupeSeconds * 1000}")
                    if(System.currentTimeMillis() - startNextDupeTime >= nextDupeSeconds * 1000 && startNextDupeTime != 0L && resetButtonPressed) {
                        startNextDupeTime = 0
                        step = 12
                    }
                    if(System.currentTimeMillis() - startNextDupeTime < 0 && resetButtonPressed){
                        startNextDupeTime = 0
                        step = 12
                    }
                    if(resetButtonPressed && player.isRiding && player.ridingEntity is EntityMinecart){
                        //player.dismountRidingEntity()
                    }
                }
                12 -> {
                    //reset vars, restart dupe
                    failed = false
                    step = 0
                    PacketCancel.disable()
                    previousCartid = 0
                    mountTimer.reset()
                    mountList.clear()
                    gasWait = false
                    dupeTime = 0L
                    resetButtonPressed = false
                    startNextDupeTime = 0L
                    gassed = false
                    dupeFinished = false

                }
            }
        }

        safeListener<PacketEvent.Receive> {
            if(it.packet is SPacketBlockChange){
                if(step == 7) {
                    val button = BlockPos(gasButtonX, gasButtonY, gasButtonZ)
                    if((it.packet as SPacketBlockChange).blockPosition == button){
                        if((it.packet as SPacketBlockChange).blockState.block == Blocks.STONE_BUTTON || (it.packet as SPacketBlockChange).blockState.block == Blocks.WOODEN_BUTTON){
                            if((it.packet as SPacketBlockChange).blockState.getValue(BlockButton.POWERED)){
                                gasWait = true
                                gassed = true
                                packetCancelEnableTime = System.currentTimeMillis()
                                if(debug) sendChatMessage("Button pressed")

                            }
                        }
                    }
                }

                if(step == 11) {
                    val button = BlockPos(resetButtonX, resetButtonY, resetButtonZ)
                    if((it.packet as SPacketBlockChange).blockPosition == button){
                        if((it.packet as SPacketBlockChange).blockState.block == Blocks.STONE_BUTTON || (it.packet as SPacketBlockChange).blockState.block == Blocks.WOODEN_BUTTON){
                            if((it.packet as SPacketBlockChange).blockState.getValue(BlockButton.POWERED)){
                                resetButtonPressed = true
                                if(debug) sendChatMessage("Button pressed")
                            }
                        }
                    }
                }
            }
        }


    }


    private fun SafeClientEvent.mountEntity(entity: Entity) {
        playerController.interactWithEntity(player, entity, EnumHand.MAIN_HAND)
        if(debug) sendChatMessage("tried to mount ${entity.entityId} distance: ${player.getDistanceSq(entity)}")
    }

    private fun SafeClientEvent.lookAtPos(vec3d: Vec3d) {
        val rotation = getRotationTo(vec3d)
        player.rotationYaw = rotation.x
        player.rotationPitch = rotation.y
    }

    private fun SafeClientEvent.tryPressButton(buttonPos: BlockPos) {
        val face = EnumFacing.DOWN
        val buttonPosUpdated = BlockPos(buttonPos.x, buttonPos.y +1, buttonPos.z)
        val hitVec = getHitVec(buttonPosUpdated, face)
        lookAtPos(hitVec)
        player.swingArm(EnumHand.MAIN_HAND)
        playerController.processRightClickBlock(player, world, buttonPos, face, hitVec, EnumHand.MAIN_HAND)
    }



}



