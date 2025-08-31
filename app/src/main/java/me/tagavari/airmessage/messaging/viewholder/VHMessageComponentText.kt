package me.tagavari.airmessage.messaging.viewholder

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.tagavari.airmessage.enums.MessageComponentType
import me.tagavari.airmessage.helper.MessageShapeHelper
import me.tagavari.airmessage.helper.ThemeHelper
import me.tagavari.airmessage.view.InvisibleInkView

class VHMessageComponentText(
	itemView: View,
	groupContainer: ViewGroup,
	stickerContainer: ViewGroup,
	tapbackContainer: ViewGroup,
	val content: ViewGroup,
	val groupMessage: ViewGroup,
	val labelBody: TextView,
	val labelSubject: TextView,
	val inkView: InvisibleInkView, //The container for the preview
	val messagePreviewContainer: ViewGroup
) : VHMessageComponent(itemView, groupContainer, stickerContainer, tapbackContainer) {
	@JvmField var messagePreviewViewHolder: VHMessagePreviewLink? = null
	override val componentType = MessageComponentType.text
	
	override fun updateViewEdges(context: Context, anchoredTop: Boolean, anchoredBottom: Boolean, alignToRight: Boolean) {
		//Checking if we have a preview
		val messagePreviewViewHolder = messagePreviewViewHolder
		if(messagePreviewViewHolder != null) {
			//Updating the message text bubble's background
			groupMessage.background = MessageShapeHelper.createRoundedMessageDrawableTop(context.resources, anchoredTop, alignToRight)
			
			//Updating the ink view's radius
			inkView.setRadii(MessageShapeHelper.createStandardRadiusArrayTop(context.resources, anchoredTop, alignToRight))
			
			//Updating the preview view's background
			messagePreviewViewHolder.updateViewEdges(context, anchoredBottom, alignToRight)
		} else {
			//Updating the message text bubble's background
			groupMessage.background = MessageShapeHelper.createRoundedMessageDrawable(context.resources, anchoredTop, anchoredBottom, alignToRight)
			
			//Updating the ink view's radius
			inkView.setRadii(MessageShapeHelper.createStandardRadiusArray(context.resources, anchoredTop, anchoredBottom, alignToRight))
		}
	}
	
	override fun updateViewColoring(context: Context, colorTextPrimary: Int, colorTextSecondary: Int, colorBackground: Int) {
		labelBody.setTextColor(colorTextPrimary)
		labelBody.setLinkTextColor(colorTextPrimary)
		labelSubject.setTextColor(colorTextPrimary)
		
		// Check if we should use custom theme backgrounds
		val currentTheme = ThemeHelper.getCurrentTheme(context)
		if (ThemeHelper.isCustomTheme(currentTheme)) {
			// For custom themes, remove the tint and apply custom drawable backgrounds
			// The custom drawables already have the colors built-in
			groupMessage.backgroundTintList = null
			// We don't know if this is outgoing or incoming here, so we'll handle this differently
			// For now, just remove the tint to let the custom drawable show through
		} else {
			// For standard themes, use the standard tint approach
			groupMessage.backgroundTintList = ColorStateList.valueOf(colorBackground)
		}
		
		inkView.setBackgroundColor(colorBackground)
	}
	
	/**
	 * Apply custom theme background drawable
	 */
	fun applyCustomBackground(context: Context, backgroundDrawableId: Int) {
		val drawable = ContextCompat.getDrawable(context, backgroundDrawableId)
		groupMessage.background = drawable
	}
}