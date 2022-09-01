import com.lambda.client.plugin.api.Plugin

internal object DoopMain: Plugin() {

    override fun onLoad() {
        // Load any modules, commands, or HUD elements here
        modules.add(AutoDupe)
        commands.add(AutoDupeCommand)
    }

    override fun onUnload() {
        // Here you can unregister threads etc...
    }
}