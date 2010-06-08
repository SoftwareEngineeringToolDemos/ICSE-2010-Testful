/*
 * TestFul - http://code.google.com/p/testful/
 * Copyright (C) 2010  Matteo Miraz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package testful.model.xml;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(namespace = "http://testful.sourceforge.net/schema/1.2/testful.xsd", name = "constructor", propOrder = { "parameter", "extra" })
public class XmlConstructor implements Comparable<XmlConstructor> {

	@XmlElement(nillable = true)
	protected List<XmlParameter> parameter;

	@XmlAttribute(required=false)
	public Boolean skip = false;

	@XmlElement
	protected List<Extra> extra;

	public List<XmlParameter> getParameter() {
		if(parameter == null) parameter = new ArrayList<XmlParameter>();
		return parameter;
	}

	public List<Extra> getExtra() {
		if(extra == null) extra = new ArrayList<Extra>();
		return extra;
	}

	/**
	 * @param skip true if testful should ignore this constructor
	 */
	public void setSkip(boolean skip) {
		this.skip = skip;
	}

	/**
	 * @return true if testful should ignore this constructor
	 */
	public boolean isSkip() {
		return skip;
	}

	/* (non-Javadoc)
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	@Override
	public int compareTo(XmlConstructor o) {
		Iterator<XmlParameter> it1 = parameter.iterator();
		Iterator<XmlParameter> it2 = o.parameter.iterator();

		while(it1.hasNext() & it2.hasNext()) {
			XmlParameter p1 = it1.next();
			XmlParameter p2 = it2.next();

			final int compare = p1.compareTo(p2);
			if(compare != 0) return compare;
		}

		if(it1.hasNext()) return -1;
		if(it2.hasNext()) return  1;
		return 0;
	}

	/**
	 * Create the XML description for a given constructor
	 * @param cns the constructor to describe
	 * @return null if the constructor should be skipped (i.e., it is not public),
	 * 			or the XML description of the constructor
	 */
	public static XmlConstructor create(Constructor<?> cns) {
		if(!Modifier.isPublic(cns.getModifiers())) return null;

		XmlConstructor xcns = testful.model.xml.ObjectFactory.factory.createConstructor();

		for(Class<?> p : cns.getParameterTypes())
			xcns.getParameter().add(XmlParameter.create(p, null));

		return xcns;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
		+ ((parameter == null) ? 0 : parameter.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		XmlConstructor other = (XmlConstructor) obj;
		if (parameter == null) {
			if (other.parameter != null)
				return false;
		} else if (!parameter.equals(other.parameter))
			return false;
		return true;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("(");

		boolean first = true;
		for (XmlParameter p : getParameter()) {
			if(first) first = false;
			else sb.append(", ");
			sb.append(p.getType());
		}

		return sb.append(")").toString();
	}
}