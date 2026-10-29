package com.shmayvan.slayerledger;

import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;

/**
 * Keeps a text field's text to at most a number of characters as the player types or pastes: text that would go past
 * the limit is cut. Used by the "Name this location" prompt.
 */
final class LengthLimitFilter extends DocumentFilter
{
	private final int maxLength;

	LengthLimitFilter(int maxLength)
	{
		this.maxLength = maxLength;
	}

	/** Limits {@code field} to {@code maxLength} characters. */
	static void apply(JTextComponent field, int maxLength)
	{
		((AbstractDocument) field.getDocument()).setDocumentFilter(new LengthLimitFilter(maxLength));
	}

	@Override
	public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException
	{
		replace(fb, offset, 0, text, attrs);
	}

	@Override
	public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
		throws BadLocationException
	{
		final int room = maxLength - (fb.getDocument().getLength() - length);
		String kept = text == null ? "" : text;
		if (kept.length() > room)
		{
			kept = kept.substring(0, Math.max(0, room));
		}
		fb.replace(offset, length, kept, attrs);
	}
}
