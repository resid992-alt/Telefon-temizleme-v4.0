package az.temiz

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class CleanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Təmiz Telefon"
            subtitle = "1 kliklə təmizlə"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            subtitle = "Təmizlənir..."
            updateTile()
        }

        val res = Cleaner.run(applicationContext)
        val mb = res.optLong("mb", 0L)
        val cnt = res.optInt("n", 0)

        Toast.makeText(
            applicationContext,
            "$cnt tətbiq dayandırıldı · ~$mb MB RAM azad edildi",
            Toast.LENGTH_SHORT
        ).show()

        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = "+$mb MB azad"
            updateTile()
        }
    }
}
