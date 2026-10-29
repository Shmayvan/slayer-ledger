package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import javax.swing.JTextField;
import org.junit.Test;

/** {@link LengthLimitFilter}: typing, pasting and replacing never take a field past its limit. */
public class LengthLimitFilterTest
{
	@Test
	public void textPastTheLimitIsCut()
	{
		JTextField field = new JTextField();
		LengthLimitFilter.apply(field, 5);
		field.setText("abcdefgh");
		assertEquals("abcde", field.getText());
		field.replaceSelection("x");
		assertEquals("full, so nothing is added", "abcde", field.getText());
		field.select(1, 3);
		field.replaceSelection("XYZW");
		assertEquals("a replaced selection makes room", "aXYde", field.getText());
		field.setText("");
		assertEquals("", field.getText());
		field.setText("ab");
		field.setCaretPosition(2);
		field.replaceSelection("cdefg");
		assertEquals("abcde", field.getText());
	}
}
