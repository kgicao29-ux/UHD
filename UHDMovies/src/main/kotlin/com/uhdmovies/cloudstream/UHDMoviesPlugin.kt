package com.uhdmovies.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class UHDMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(UHDMoviesProvider())
    }
}
