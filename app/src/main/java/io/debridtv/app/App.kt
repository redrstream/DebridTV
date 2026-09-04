package io.debridtv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.debridtv.app.di.ServiceLocator

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }

    // A single app-wide Coil loader so EVERY AsyncImage (posters, episode thumbs,
    // the detail backdrop) fades in instead of snapping. The fade is what makes
    // scrolling feel smooth — cards arrive gently rather than popping. Cheap: just
    // a short crossfade + Coil's default memory cache, no extra work per image.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .crossfade(220)
            .build()
}
