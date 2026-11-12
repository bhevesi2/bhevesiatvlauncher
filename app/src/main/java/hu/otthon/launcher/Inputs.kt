package hu.otthon.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** An HDMI input; [device] is the name the device plugged into it announces over HDMI-CEC, if any. */
data class TvInput(val id: String, val name: String, val device: String? = null)

/** The TV's physical HDMI inputs and the built-in tuner. */
object Inputs {
    private const val SONY_TV_APP = "com.sony.dtv.tvx"

    /** Every HDMI input the TV reports, with the TV's own names. */
    fun all(ctx: Context): List<TvInput> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            val list = tim.tvInputList
            // a device that speaks HDMI-CEC (a console, a player, a receiver) is listed as a child of its port
            val devices = list.filter { it.parentId != null }.groupBy { it.parentId }
            list.filter { it.isPassthroughInput && it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
                .sortedBy { it.id }
                .mapIndexed { i, info ->
                    val own = try { info.loadLabel(ctx)?.toString() } catch (e: Exception) { null }
                    val device = devices[info.id]?.mapNotNull { d ->
                        try { d.loadLabel(ctx)?.toString()?.ifBlank { null } } catch (e: Exception) { null }
                    }?.firstOrNull()
                    TvInput(info.id, if (own.isNullOrBlank()) "HDMI ${i + 1}" else own, device)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Inputs to show on the home screen: hidden ones removed, custom names applied. */
    fun visible(ctx: Context, cfg: Config): List<TvInput> =
        all(ctx).filter { it.id !in cfg.inputsHidden }.map { TvInput(it.id, cfg.inputNames[it.id] ?: it.name, it.device) }

    fun open(ctx: Context, id: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun tvIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        pm.getLeanbackLaunchIntentForPackage(SONY_TV_APP)?.let { return it }
        val generic = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
        return if (generic.resolveActivity(pm) != null) generic else null
    }

    fun hasTv(ctx: Context): Boolean = tvIntent(ctx) != null

    fun openTv(ctx: Context): Boolean {
        val i = tvIntent(ctx) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }
}
