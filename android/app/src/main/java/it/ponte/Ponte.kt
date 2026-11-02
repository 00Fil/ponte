package it.ponte

import android.content.Context

/** Un solo Router per processo, condiviso da servizio, attività, riquadro e ricevitori. */
object Ponte {
    @Volatile
    var router: Router? = null
        private set

    fun ensure(ctx: Context): Router = router ?: synchronized(this) {
        router ?: Router(ctx.applicationContext, Prefs(ctx.applicationContext)).also { router = it; Eq.start(ctx.applicationContext) }
    }
}
