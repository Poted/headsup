package app.headsup

import android.app.Application
import app.headsup.export.ExportManager

class HeadsupApp : Application() {
    val exportManager: ExportManager by lazy { ExportManager(this) }
}
