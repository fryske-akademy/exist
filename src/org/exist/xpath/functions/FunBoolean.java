/* eXist Native XML Database
 * Copyright (C) 2000-03,  Wolfgang M. Meier (wolfgang@exist-db.org)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 */

package org.exist.xpath.functions;

import org.exist.dom.QName;
import org.exist.xpath.Cardinality;
import org.exist.xpath.Function;
import org.exist.xpath.FunctionSignature;
import org.exist.xpath.XQueryContext;
import org.exist.xpath.XPathException;
import org.exist.xpath.value.BooleanValue;
import org.exist.xpath.value.Item;
import org.exist.xpath.value.Sequence;
import org.exist.xpath.value.SequenceType;
import org.exist.xpath.value.Type;

/**
 * xpath-library function: boolean(object)
 *
 */
public class FunBoolean extends Function {

	public final static FunctionSignature signature =
		new FunctionSignature(
			new QName("boolean", BUILTIN_FUNCTION_NS),
            "Computes the xs:boolean value of the sequence argument.",
			new SequenceType[] { new SequenceType(Type.ITEM, Cardinality.ZERO_OR_MORE) },
			new SequenceType(Type.BOOLEAN, Cardinality.ONE)
		);
		
	public FunBoolean(XQueryContext context) {
		super(context, signature);
	}
	
	public int returnsType() {
		return Type.BOOLEAN;
	}
	
	public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
		if(contextItem != null)
			contextSequence = contextItem.toSequence();
		contextSequence = getArgument(0).eval(contextSequence);
		return contextSequence.effectiveBooleanValue() ? BooleanValue.TRUE : BooleanValue.FALSE;
	}

	public String pprint() {
		StringBuffer buf = new StringBuffer();
		buf.append("boolean(");
		buf.append(getArgument(0).pprint());
		buf.append(")");
		return buf.toString();
	}
}
