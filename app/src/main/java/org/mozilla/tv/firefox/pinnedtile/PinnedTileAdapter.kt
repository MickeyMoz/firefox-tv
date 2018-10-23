/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.pinnedtile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.support.v4.content.ContextCompat
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlinx.android.synthetic.main.home_tile.view.*
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.mozilla.tv.firefox.R
import org.mozilla.tv.firefox.ext.forceExhaustive
import org.mozilla.tv.firefox.ext.toJavaURI
import org.mozilla.tv.firefox.ext.withRoundedCorners
import org.mozilla.tv.firefox.telemetry.TelemetryIntegration
import org.mozilla.tv.firefox.utils.FormattedDomain

/**
 * Duration of animation to show custom tile. If the duration is too short, the tile will just
 * pop-in. I speculate this happens because the amount of time it takes to downsample the bitmap
 * is longer than the animation duration.
 */
private const val CUSTOM_TILE_TO_SHOW_MILLIS = 200L
private val CUSTOM_TILE_ICON_INTERPOLATOR = DecelerateInterpolator()

class PinnedTileAdapter(
    private val uiLifecycleCancelJob: Job,
    private var tiles: MutableList<PinnedTile>,
    private val loadUrl: (String) -> Unit,
    var onTileLongClick: (() -> Unit)?,
    var onTileFocused: (() -> Unit)?
) : RecyclerView.Adapter<TileViewHolder>() {

    var lastLongClickedTile: PinnedTile? = null
        private set

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) = with(holder) {
        val item = tiles[position]
        when (item) {
            is BundledPinnedTile -> {
                onBindBundledHomeTile(holder, item)
                setIconLayoutMarginParams(iconView, R.dimen.bundled_home_tile_margin_value)
            }
            is CustomPinnedTile -> {
                onBindCustomHomeTile(uiLifecycleCancelJob, holder, item)
                setIconLayoutMarginParams(iconView, R.dimen.custom_home_tile_margin_value)
            }
        }.forceExhaustive

        itemView.setOnClickListener {
            loadUrl(item.url)
            TelemetryIntegration.INSTANCE.homeTileClickEvent(it.context, item)
        }

        itemView.setOnLongClickListener {
            onTileLongClick?.invoke()
            lastLongClickedTile = item

            true
        }

        val tvWhiteColor = ContextCompat.getColor(holder.itemView.context, R.color.tv_white)
        itemView.setOnFocusChangeListener { _, hasFocus ->
            val backgroundResource: Int
            val textColor: Int
            if (hasFocus) {
                backgroundResource = R.drawable.home_tile_title_focused_background
                textColor = tvWhiteColor
                onTileFocused?.invoke()
            } else {
                backgroundResource = 0
                textColor = Color.BLACK
            }
            titleView.setBackgroundResource(backgroundResource)
            titleView.setTextColor(textColor)
        }
    }

    private fun setIconLayoutMarginParams(iconView: View, tileMarginValue: Int) {
        val layoutMarginParams = iconView.layoutParams as ViewGroup.MarginLayoutParams
        val marginValue = iconView.resources.getDimensionPixelSize(tileMarginValue)
        layoutMarginParams.setMargins(marginValue, marginValue, marginValue, marginValue)
        iconView.layoutParams = layoutMarginParams
    }

    /**
     * takes in the home tiles cache and updates the adapter's data source
     * and UI accordingly, assuming only one new tile is added
     */
    fun updateAdapterSingleInsertion(pinnedTiles: MutableList<PinnedTile>) {
        if (pinnedTiles.size == tiles.size) {
            // The lists must not be the same size in order
            // for an insertion to be valid
            return
        }
        for ((index, tile) in tiles.withIndex()) {
            // Due to insertion, the inserted tile will be
            // the first tile that will not match the
            // previous list of tiles
            if (tile != pinnedTiles[index]) {
                tiles = pinnedTiles
                notifyItemInserted(index)
                return
            }
        }
        tiles = pinnedTiles
        if (tiles.size > 1) {
            notifyItemInserted(pinnedTiles.lastIndex)
        } else {
            // the entire DataSet needs to be notified when adding to an empty container
            notifyDataSetChanged()
        }
    }

    fun removeTile(tileId: String) {
        for ((index, tile) in tiles.withIndex()) {
            if (tile is CustomPinnedTile && tile.id.toString() == tileId || tile is BundledPinnedTile && tile.id == tileId) {
                removeTile(index)
                break
            }
        }
    }

    fun removeTile(position: Int) {
        if (position > -1 && position < itemCount) {
            tiles.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemCount() = tiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TileViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.home_tile, parent, false)
    )
}

private fun onBindBundledHomeTile(holder: TileViewHolder, tile: BundledPinnedTile) = with(holder) {
    val bitmap = BundledTilesManager.getInstance(itemView.context).loadImageFromPath(itemView.context, tile.imagePath)
    iconView.setImageBitmap(bitmap)

    titleView.text = tile.title
}

private fun onBindCustomHomeTile(uiLifecycleCancelJob: Job, holder: TileViewHolder, item: CustomPinnedTile) = with(holder) {
    launch(uiLifecycleCancelJob + UI, CoroutineStart.UNDISPATCHED) {
        val validUri = item.url.toJavaURI()

        val screenshotDeferred = async {
            val homeTileCornerRadius = itemView.resources.getDimension(R.dimen.home_tile_corner_radius)
            val homeTilePlaceholderCornerRadius = itemView.resources.getDimension(R.dimen.home_tile_placeholder_corner_radius)
            val screenshot = PinnedTileScreenshotStore.read(itemView.context, item.id)?.withRoundedCorners(homeTileCornerRadius)
            screenshot ?: PinnedTilePlaceholderGenerator.generate(itemView.context, item.url)
                    .withRoundedCorners(homeTilePlaceholderCornerRadius)
        }

        val titleDeferred = if (validUri == null) {
            CompletableDeferred(item.url)
        } else {
            async {
                val subdomainDotDomain = FormattedDomain.format(itemView.context, validUri, false, 1)
                FormattedDomain.stripCommonPrefixes(subdomainDotDomain)
            }
        }

        // We wait for both to complete so we can animate them together.
        val screenshot = screenshotDeferred.await()
        val title = titleDeferred.await()

        // NB: Don't suspend after this point (i.e. between view updates like setImage)
        // so we don't see intermediate view states.
        // TODO: It'd be less error-prone to launch { /* bg work */ launch(UI) { /* UI work */ } }
        iconView.setImageBitmap(screenshot)
        titleView.text = title

        // Animate to avoid pop-in due to thread hand-offs. TODO: animation is janky.
        AnimatorSet().apply {
            interpolator = CUSTOM_TILE_ICON_INTERPOLATOR
            duration = CUSTOM_TILE_TO_SHOW_MILLIS

            val iconAnim = ObjectAnimator.ofInt(iconView, "imageAlpha", 0, 255)
            val titleAnim = ObjectAnimator.ofFloat(titleView, "alpha", 0f, 1f)

            playTogether(iconAnim, titleAnim)
        }.start()
    }
}

class TileViewHolder(
    itemView: View
) : RecyclerView.ViewHolder(itemView) {
    val iconView = itemView.tile_icon
    val titleView = itemView.tile_title
}
