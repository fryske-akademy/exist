/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-03,  Wolfgang M. Meier (wolfgang@exist-db.org)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU Library General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 *  $Id$
 */
package org.exist.dom;

import java.util.Comparator;
import java.util.Iterator;

import org.exist.memtree.Receiver;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.Serializer;
import org.exist.xpath.XPathException;
import org.exist.xpath.value.AtomicValue;
import org.exist.xpath.value.Item;
import org.exist.xpath.value.NodeValue;
import org.exist.xpath.value.Sequence;
import org.exist.xpath.value.SequenceIterator;
import org.exist.xpath.value.StringValue;
import org.exist.xpath.value.Type;
import org.exist.xpath.value.UntypedAtomicValue;
import org.w3c.dom.Node;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Placeholder class for DOM nodes. 
 * 
 * NodeProxy is an internal proxy class, acting as a placeholder for all types of XML nodes
 * during query processing. NodeProxy just stores the node's unique id and the document it belongs to. 
 * Query processing deals with these proxys most of the time. Using a NodeProxy is much cheaper 
 * than loading the actual node from the database. The real DOM node is only loaded,
 * if further information is required for the evaluation of an XPath expression. To obtain 
 * the real node for a proxy, simply call {@link #getNode()}. 
 * 
 * All sets of type NodeSet operate on NodeProxys. A node set is a special type of 
 * sequence, so NodeProxy does also implement {@link org.exist.xpath.value.Item} and
 * can thus be an item in a sequence. Since, according to XPath 2, a single node is also 
 * a sequence, NodeProxy does itself extend NodeSet. It thus represents a node set containing
 * just one, single node.
 *
 *@author     Wolfgang Meier <wolfgang@exist-db.org>
 */
public final class NodeProxy extends AbstractNodeSet implements NodeValue, Comparable {

	/**
	 * The owner document of this node.
	 */
	public DocumentImpl doc = null;

	/**
	 * The unique internal node id.
	 */
	public long gid = 0;

	/**
	 * The type of this node (as defined by DOM), if known, -1 if
	 * unknown.
	 */
	public short nodeType = -1;

	/**
	 * The first {@link Match} object associated with this node.
	 * Match objects are used to track fulltext hits throughout query processing.
	 * 
	 * Matches are stored as a linked list.
	 */
	public Match match = null;

	private ContextItem context = null;
	private long internalAddress = -1;

	public NodeProxy() {
	}

	/**
	 *  Construct a node proxy with unique id gid and owned by document doc.
	 *
	 *@param  doc  Description of the Parameter
	 *@param  gid  Description of the Parameter
	 */
	public NodeProxy(DocumentImpl doc, long gid) {
		this.doc = doc;
		this.gid = gid;
	}

	/**
	 *  as above, but a hint is given about the node type of this proxy-object.
	 *
	 *@param  doc       Description of the Parameter
	 *@param  gid       Description of the Parameter
	 *@param  nodeType  Description of the Parameter
	 */
	public NodeProxy(DocumentImpl doc, long gid, short nodeType) {
		this.doc = doc;
		this.gid = gid;
		this.nodeType = nodeType;
	}

	public NodeProxy(DocumentImpl doc, long gid, short nodeType, long address) {
		this.doc = doc;
		this.gid = gid;
		this.nodeType = nodeType;
		this.internalAddress = address;
	}

	public NodeProxy(DocumentImpl doc, long gid, long address) {
		this.gid = gid;
		this.doc = doc;
		this.internalAddress = address;
	}

	public NodeProxy(NodeProxy p) {
		doc = p.doc;
		gid = p.gid;
		nodeType = p.nodeType;
		match = p.match;
		internalAddress = p.internalAddress;
	}

	public NodeProxy(NodeImpl node) {
		this((DocumentImpl) node.getOwnerDocument(), node.getGID());
		internalAddress = node.getInternalAddress();
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.NodeValue#getImplementation()
	 */
	public int getImplementationType() {
		return NodeValue.PERSISTENT_NODE;
	}

	public int compareTo(NodeProxy other) {
		final int diff = doc.docId - other.doc.docId;
		return diff == 0 ? (gid < other.gid ? -1 : (gid > other.gid ? 1 : 0)) : diff;
	}

	public int compareTo(Object other) {
		final NodeProxy p = (NodeProxy) other;
		if (doc.docId == p.doc.docId) {
			if (gid == p.gid)
				return 0;
			else if (gid < p.gid)
				return -1;
			else
				return 1;
		} else if (doc.docId < p.doc.docId)
			return -1;
		else
			return 1;
	}

	public boolean equals(Object other) {
		if (!(other instanceof NodeProxy))
			throw new RuntimeException("cannot compare nodes from different implementations");
		NodeProxy node = (NodeProxy) other;
		if (node.doc.getDocId() == doc.getDocId() && node.gid == gid)
			return true;
		return false;
	}

	public boolean equals(NodeValue other) throws XPathException {
		if (other.getImplementationType() != NodeValue.PERSISTENT_NODE)
			throw new XPathException("cannot compare persistent node with in-memory node");
		NodeProxy node = (NodeProxy) other;
		if (node.doc.getDocId() == doc.getDocId() && node.gid == gid)
			return true;
		return false;
	}

	public boolean before(NodeValue other) throws XPathException {
		if (other.getImplementationType() != NodeValue.PERSISTENT_NODE)
			throw new XPathException("cannot compare persistent node with in-memory node");
		NodeProxy node = (NodeProxy) other;
		if (doc.docId != node.doc.docId)
			return false;
		//		System.out.println(gid + " << " + node.gid);
		int la = doc.getTreeLevel(gid);
		int lb = doc.getTreeLevel(node.gid);
		long pa = gid, pb = node.gid;
		if (la > lb) {
			while (la > lb) {
				pa = XMLUtil.getParentId(doc, pa, la);
				--la;
			}
			if (pa == pb)
				return false;
			else
				return pa < pb;
		} else if (lb > la) {
			while (lb > la) {
				pb = XMLUtil.getParentId(node.doc, pb, lb);
				--lb;
			}
			if (pb == pa)
				return true;
			else
				return pa < pb;
		} else
			return pa < pb;
	}

	public boolean after(NodeValue other) throws XPathException {
		if (other.getImplementationType() != NodeValue.PERSISTENT_NODE)
			throw new XPathException("cannot compare persistent node with in-memory node");
		NodeProxy node = (NodeProxy) other;
		if (doc.docId != node.doc.docId)
			return false;
		//		System.out.println(gid + " >> " + node.gid);
		int la = doc.getTreeLevel(gid);
		int lb = doc.getTreeLevel(node.gid);
		long pa = gid, pb = node.gid;
		if (la > lb) {
			while (la > lb) {
				pa = XMLUtil.getParentId(doc, pa, la);
				--la;
			}
			if (pa == pb)
				return true;
			else
				return pa > pb;
		} else if (lb > la) {
			while (lb > la) {
				pb = XMLUtil.getParentId(node.doc, pb, lb);
				--lb;
			}
			if (pb == pa)
				return false;
			else
				return pa > pb;
		} else
			return pa > pb;
	}

	public DocumentImpl getDoc() {
		return doc;
	}

	public long getGID() {
		return gid;
	}

	public Node getNode() {
		return doc.getNode(this);
	}
	
	public short getNodeType() {
		return nodeType;
	}

	public String getNodeValue() {
		return doc.getBroker().getNodeValue(this);
	}

	public void setGID(long gid) {
		this.gid = gid;
	}

	public String toString() {
		return doc.getNode(gid).toString();
	}

	public static class NodeProxyComparator implements Comparator {

		public static NodeProxyComparator instance = new NodeProxyComparator();

		public int compare(Object obj1, Object obj2) {
			if (obj1 == null || obj2 == null)
				throw new NullPointerException("cannot compare null values");
			if (!(obj1 instanceof NodeProxy && obj2 instanceof NodeProxy))
				throw new RuntimeException(
					"cannot compare nodes " + "from different implementations");
			NodeProxy p1 = (NodeProxy) obj1;
			NodeProxy p2 = (NodeProxy) obj2;
			if (p1.doc.docId == p2.doc.docId) {
				if (p1.gid == p2.gid)
					return 0;
				else if (p1.gid < p2.gid)
					return -1;
				else
					return 1;
			} else if (p1.doc.docId < p2.doc.docId)
				return -1;
			else
				return 1;
		}
	}

	/**
		 * Sets the doc this node belongs to.
		 * @param doc The doc to set
		 */
	public void setDoc(DocumentImpl doc) {
		this.doc = doc;
	}

	/**
		 * Sets the nodeType.
		 * @param nodeType The nodeType to set
		 */
	public void setNodeType(short nodeType) {
		this.nodeType = nodeType;
	}

	/**
		 * Returns the storage address of this node in dom.dbx.
		 * @return long
		 */
	public long getInternalAddress() {
		return internalAddress;
	}

	/**
	 * Sets the storage address of this node in dom.dbx.
	 * 
	 * @param internalAddress The internalAddress to set
	 */
	public void setInternalAddress(long internalAddress) {
		this.internalAddress = internalAddress;
	}

	public void setHasIndex(boolean hasIndex) {
		internalAddress =
			(hasIndex ? internalAddress | 0x10000L : internalAddress & (~0x10000L));
	}

	public boolean hasIndex() {
		return (internalAddress & 0x10000L) > 0;
	}

	public boolean hasMatch(Match m) {
		if (m == null || match == null)
			return false;
		Match next = match;
		do {
			if (next.equals(m))
				return true;
		} while ((next = next.getNextMatch()) != null);
		return false;
	}

	public void addMatch(Match m) {
		if (match == null) {
			match = m;
			match.prevMatch = null;
			match.nextMatch = null;
			return;
		}
		Match next = match;
		int cmp;
		while (next != null) {
			cmp = m.compareTo(next);
			if (cmp == 0 && m.getNodeId() == next.getNodeId())
				return;
			else if (cmp < 0) {
				if (next.prevMatch != null)
					next.prevMatch.nextMatch = m;
				else
					match = m;
				m.prevMatch = next.prevMatch;
				next.prevMatch = m;
				m.nextMatch = next;
				return;
			} else if (next.nextMatch == null) {
				next.nextMatch = m;
				m.prevMatch = next;
				m.nextMatch = null;
				return;
			}
			next = next.nextMatch;
		}
	}

	public void addMatches(Match m) {
		Match next;
		while (m != null) {
			next = m.nextMatch;
			addMatch(m);
			m = next;
		}
		//printMatches();
	}

	public void printMatches() {
		System.out.print(gid);
		System.out.print(": ");
		Match next = match;
		while (next != null) {
			System.out.print(next.getMatchingTerm());
			System.out.print(" ");
			next = next.nextMatch;
		}
		System.out.println();
	}

	/**
	 * Add a node to the list of context nodes for this node.
	 * 
	 * NodeProxy internally stores the context nodes of the XPath context, for which 
	 * this node has been selected during a previous processing step.
	 * 
	 * Since eXist tries to process many expressions in one, single processing step,
	 * the context information is required to resolve predicate expressions. For
	 * example, for an expression like //SCENE[SPEECH/SPEAKER='HAMLET'],
	 * we have to remember the SCENE nodes for which the equality expression
	 * in the predicate was true.  Thus, when evaluating the step SCENE[SPEECH], the
	 * SCENE nodes become context items of the SPEECH nodes and this context
	 * information is preserved through all following steps.
	 * 
	 * To process the predicate expression, {@link org.exist.xpath.Predicate} will take the
	 * context nodes returned by the filter expression and compare them to its context
	 * node set.
	 */
	public void addContextNode(NodeProxy node) {
		if (context == null) {
			context = new ContextItem(node);
			//			Thread.dumpStack();
			return;
		}
		ContextItem next = context;
		while (next != null) {
			if (next.getNode().gid == node.gid)
				break;
			if (next.getNextItem() == null) {
				next.setNextItem(new ContextItem(node));
				break;
			}
			next = next.getNextItem();
		}
		//		Thread.dumpStack();
	}

	public void clearContext() {
		context = null;
	}

	public void printContext() {
		ContextItem next = context;
		System.out.print(hashCode() + " " + gid + ": ");
		while (next != null) {
			System.out.print(next.getNode().gid);
			System.out.print(' ');
			next = next.getNextItem();
		}
		System.out.println();
	}
	public void copyContext(NodeProxy node) {
		context = node.getContext();
	}

	public ContextItem getContext() {
		return context;
	}

	//	methods of interface Item

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#getType()
	 */
	public int getType() {
		switch (nodeType) {
			case Node.ELEMENT_NODE :
				return Type.ELEMENT;
			case Node.ATTRIBUTE_NODE :
				return Type.ATTRIBUTE;
			case Node.TEXT_NODE :
				return Type.TEXT;
			case Node.PROCESSING_INSTRUCTION_NODE :
				return Type.PROCESSING_INSTRUCTION;
			case Node.COMMENT_NODE :
				return Type.COMMENT;
			default :
				return Type.NODE; // unknown type
		}
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#toSequence()
	 */
	public Sequence toSequence() {
		return this;
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#getStringValue()
	 */
	public String getStringValue() {
		return getNodeValue();
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#convertTo(int)
	 */
	public AtomicValue convertTo(int requiredType) throws XPathException {
		return new StringValue(getNodeValue()).convertTo(requiredType);
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#atomize()
	 */
	public AtomicValue atomize() throws XPathException {
		return new UntypedAtomicValue(getNodeValue());
	}

	/* -----------------------------------------------*
	 * Methods of class NodeSet
	 * -----------------------------------------------*/

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#iterator()
	 */
	public Iterator iterator() {
		return new SingleNodeIterator(this);
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#iterate()
	 */
	public SequenceIterator iterate() {
		return new SingleNodeIterator(this);
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#contains(org.exist.dom.DocumentImpl, long)
	 */
	public boolean contains(DocumentImpl doc, long nodeId) {
		return this.doc.getDocId() == doc.getDocId() && this.gid == nodeId;
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#contains(org.exist.dom.NodeProxy)
	 */
	public boolean contains(NodeProxy proxy) {
		return doc.getDocId() == proxy.doc.getDocId() && gid == proxy.gid;
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#addAll(org.exist.dom.NodeSet)
	 */
	public void addAll(NodeSet other) {
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#add(org.exist.dom.NodeProxy)
	 */
	public void add(NodeProxy proxy) {
	}

	/* (non-Javadoc)
	 * @see org.w3c.dom.NodeList#getLength()
	 */
	public int getLength() {
		return 1;
	}

	/* (non-Javadoc)
	 * @see org.w3c.dom.NodeList#item(int)
	 */
	public Node item(int pos) {
		return pos > 0 ? null : getNode();
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Sequence#itemAt(int)
	 */
	public Item itemAt(int pos) {
		return pos > 0 ? null : this;
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#get(int)
	 */
	public NodeProxy get(int pos) {
		return pos > 0 ? null : this;
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#get(org.exist.dom.NodeProxy)
	 */
	public NodeProxy get(NodeProxy p) {
		return contains(p) ? this : null;
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#get(org.exist.dom.DocumentImpl, long)
	 */
	public NodeProxy get(DocumentImpl doc, long nodeId) {
		return contains(doc, nodeId) ? this : null;
	}

	public void toSAX(DBBroker broker, ContentHandler handler) throws SAXException {
		Serializer serializer = broker.getSerializer();
		serializer.reset();
		serializer.setProperty(Serializer.GENERATE_DOC_EVENTS, "false");
		serializer.setContentHandler(handler);
		serializer.toSAX(this);
	}

	public void copyTo(DBBroker broker, Receiver receiver) throws SAXException {
		if (nodeType == Node.ATTRIBUTE_NODE) {
			AttrImpl attr = (AttrImpl) getNode();
			receiver.attribute(attr.getQName(), attr.getValue());
		} else
			toSAX(broker, receiver);
	}

	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#conversionPreference(java.lang.Class)
	 */
	public int conversionPreference(Class javaClass) {
		if (javaClass.isAssignableFrom(NodeProxy.class))
			return 0;
		if (javaClass.isAssignableFrom(Node.class))
			return 1;
		if (javaClass == String.class || javaClass == CharSequence.class)
			return 2;
		if (javaClass == Character.class || javaClass == char.class)
			return 2;
		if (javaClass == Double.class || javaClass == double.class)
			return 10;
		if (javaClass == Float.class || javaClass == float.class)
			return 11;
		if (javaClass == Long.class || javaClass == long.class)
			return 12;
		if (javaClass == Integer.class || javaClass == int.class)
			return 13;
		if (javaClass == Short.class || javaClass == short.class)
			return 14;
		if (javaClass == Byte.class || javaClass == byte.class)
			return 15;
		if (javaClass == Boolean.class || javaClass == boolean.class)
			return 16;
		if (javaClass == Object.class)
			return 20;

		return Integer.MAX_VALUE;
	}
	
	/* (non-Javadoc)
	 * @see org.exist.xpath.value.Item#toJavaObject(java.lang.Class)
	 */
	public Object toJavaObject(Class target) throws XPathException {
		if (target.isAssignableFrom(NodeProxy.class))
			return this;
		else if (target.isAssignableFrom(Node.class))
			return getNode();
		else if (target == Object.class)
			return getNode();
		else {
			StringValue v = new StringValue(getStringValue());
			return v.toJavaObject(target);
		}
	}

	private final static class SingleNodeIterator implements Iterator, SequenceIterator {

		private boolean hasNext = true;
		private NodeProxy node;

		public SingleNodeIterator(NodeProxy node) {
			this.node = node;
		}

		public boolean hasNext() {
			return hasNext;
		}

		public Object next() {
			if (hasNext) {
				hasNext = false;
				return node;
			} else
				return null;
		}

		public void remove() {
			throw new RuntimeException("not supported");
		}

		/* (non-Javadoc)
		 * @see org.exist.xpath.value.SequenceIterator#nextItem()
		 */
		public Item nextItem() {
			if (hasNext) {
				hasNext = false;
				return node;
			} else
				return null;
		}

	}
}
