import com.lambda.client.command.ClientCommand
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage



object AutoDupeCommand: ClientCommand(
    name = "dupe",
    description = "$$$$$$"
) {
    init {
        literal("startMinecart") {
            blockPos("BlockPos") { target ->
                execute("Update position") {
                    AutoDupe.startMinecartX = target.value.x
                    AutoDupe.startMinecartY = target.value.y
                    AutoDupe.startMinecartZ = target.value.z
                    sendChatMessage("Start minecart position set to [${target.value.x}, ${target.value.y}, ${target.value.z}] ")
                }
            }
        }

        literal("returnMinecart") {
            blockPos("BlockPos") { target ->
                execute("Update position") {
                    AutoDupe.returnMinecartX = target.value.x
                    AutoDupe.returnMinecartY = target.value.y
                    AutoDupe.returnMinecartZ = target.value.z
                    sendChatMessage("Return minecart position set to [${target.value.x}, ${target.value.y}, ${target.value.z}] ")
                }
            }
        }

        literal("gasButton") {
            blockPos("BlockPos") { target ->
                execute("Update position") {
                    AutoDupe.gasButtonX = target.value.x
                    AutoDupe.gasButtonY = target.value.y
                    AutoDupe.gasButtonZ = target.value.z
                    sendChatMessage("Gas Button position set to [${target.value.x}, ${target.value.y}, ${target.value.z}] ")
                }
            }
        }

        literal("resetButton") {
            blockPos("BlockPos") { target ->
                execute("Update position") {
                    AutoDupe.resetButtonX = target.value.x
                    AutoDupe.resetButtonY = target.value.y
                    AutoDupe.resetButtonZ = target.value.z
                    sendChatMessage("Reset Button position set to [${target.value.x}, ${target.value.y}, ${target.value.z}] ")
                }
            }
        }

    }
}
