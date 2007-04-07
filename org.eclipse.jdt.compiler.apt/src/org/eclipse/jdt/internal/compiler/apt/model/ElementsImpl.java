/*******************************************************************************
 * Copyright (c) 2006 BEA Systems, Inc. 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    wharley@bea.com - initial API and implementation
 *    
 *******************************************************************************/

package org.eclipse.jdt.internal.compiler.apt.model;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

import org.eclipse.jdt.internal.compiler.apt.dispatch.BaseProcessingEnvImpl;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodVerifier;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;

/**
 * Utilities for working with language elements.
 * There is one of these for every ProcessingEnvironment.
 */
public class ElementsImpl implements Elements {
	
	private final BaseProcessingEnvImpl _env;
	
	/*
	 * The processing env creates and caches an ElementsImpl.  Other clients should
	 * not create their own; they should ask the env for it.
	 */
	public ElementsImpl(BaseProcessingEnvImpl env) {
		_env = env;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getAllAnnotationMirrors(javax.lang.model.element.Element)
	 */
	@Override
	public List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Compute a list of all the visible entities in this type.  Specifically:
	 * <ul>
	 * <li>All nested types declared in this type, including interfaces and enums</li>
	 * <li>All protected or public nested types declared in this type's superclasses 
	 * and superinterfaces, that are not hidden by a name collision</li>
	 * <li>All methods declared in this type, including constructors but not
	 * including static or instance initializers, and including abstract
	 * methods and unimplemented methods declared in interfaces</li>
	 * <li>All protected or public methods declared in this type's superclasses,
	 * that are not overridden by another method, but not including constructors
	 * or initializers.  Includes abstract methods and methods declared in 
	 * superinterfaces but not implemented</li>
	 * <li>All fields declared in this type, including constants</li>
	 * <li>All non-private fields declared in this type's superclasses and
	 * superinterfaces, that are not hidden by a name collision.</li>
	 * </ul>
	 */
	@Override
	public List<? extends Element> getAllMembers(TypeElement type) {
		if (null == type || !(type instanceof TypeElementImpl)) {
			return Collections.emptyList();
		}
		ReferenceBinding binding = (ReferenceBinding)((TypeElementImpl)type)._binding;
		// Map of element simple name to binding
		Map<String, ReferenceBinding> types = new HashMap<String, ReferenceBinding>();
		// Javac implementation does not take field name collisions into account
		List<FieldBinding> fields = new ArrayList<FieldBinding>();
		// For methods, need to compare parameters, not just names
		Map<String, Set<MethodBinding>> methods = new HashMap<String, Set<MethodBinding>>();
		Set<ReferenceBinding> superinterfaces = new LinkedHashSet<ReferenceBinding>();
		boolean ignoreVisibility = true;
		while (null != binding) {
			addMembers(binding, ignoreVisibility, types, fields, methods);
			Set<ReferenceBinding> newfound = new LinkedHashSet<ReferenceBinding>();
			collectSuperInterfaces(binding, superinterfaces, newfound);
			for (ReferenceBinding superinterface : newfound) {
				addMembers(superinterface, false, types, fields, methods);
			}
			superinterfaces.addAll(newfound);
			binding = binding.superclass();
			ignoreVisibility = false;
		}
		List<Element> allMembers = new ArrayList<Element>();
		for (ReferenceBinding nestedType : types.values()) {
			allMembers.add(Factory.newElement(nestedType));
		}
		for (FieldBinding field : fields) {
			allMembers.add(Factory.newElement(field));
		}
		for (Set<MethodBinding> sameNamedMethods : methods.values()) {
			for (MethodBinding method : sameNamedMethods) {
				allMembers.add(Factory.newElement(method));
			}
		}
		return allMembers;
	}
	
	/**
	 * Recursively depth-first walk the tree of superinterfaces of a type, collecting
	 * all the unique superinterface bindings.  (Note that because of generics, a type may
	 * have multiple unique superinterface bindings corresponding to the same interface
	 * declaration.)
	 * @param existing bindings already in this set will not be re-added or recursed into
	 * @param newfound newly found bindings will be added to this set
	 */
	private void collectSuperInterfaces(ReferenceBinding type, 
			Set<ReferenceBinding> existing, Set<ReferenceBinding> newfound) {
		for (ReferenceBinding superinterface : type.superInterfaces()) {
			if (!existing.contains(superinterface) && !newfound.contains(superinterface)) {
				newfound.add(superinterface);
				collectSuperInterfaces(superinterface, existing, newfound);
			}
		}
	}

	/**
	 * Add the members of a type to the maps of subtypes, fields, and methods.  Add only those
	 * which are non-private and which are not overridden by an already-discovered member. 
	 * For fields, add them all; javac implementation does not take field hiding into account.
	 * @param binding the type whose members will be added to the lists
	 * @param ignoreVisibility if true, all members will be added regardless of whether they
	 * are private, overridden, etc.
	 * @param types a map of type simple name to type binding
	 * @param fields a list of field bindings
	 * @param methods a map of method simple name to set of method bindings with that name
	 */
	private void addMembers(ReferenceBinding binding, boolean ignoreVisibility, Map<String, ReferenceBinding> types,
			List<FieldBinding> fields, Map<String, Set<MethodBinding>> methods)
	{
		for (ReferenceBinding subtype : binding.memberTypes()) {
			if (ignoreVisibility || !subtype.isPrivate()) {
				String name = new String(subtype.sourceName());
				if (null == types.get(name)) {
					types.put(name, subtype);
				}
			}
		}
		for (FieldBinding field : binding.fields()) {
			if (ignoreVisibility || !field.isPrivate()) {
				fields.add(field);
			}
		}
		for (MethodBinding method : binding.methods()) {
			if (!method.isSynthetic() && (ignoreVisibility || (!method.isPrivate() && !method.isConstructor()))) {
				String methodName = new String(method.selector);
				Set<MethodBinding> sameNamedMethods = methods.get(methodName);
				if (null == sameNamedMethods) {
					// New method name.  Create a set for it and add it to the list.
					// We don't expect many methods with same name, so only 4 slots:
					sameNamedMethods = new HashSet<MethodBinding>(4); 
					methods.put(methodName, sameNamedMethods);
					sameNamedMethods.add(method);
				}
				else {
					// We already have a method with this name.  Is this method overridden?
					boolean unique = true;
					if (!ignoreVisibility) {
						for (MethodBinding existing : sameNamedMethods) {
							MethodVerifier verifier = _env.getLookupEnvironment().methodVerifier();
							if (verifier.doesMethodOverride(existing, method)) {
								unique = false;
								break;
							}
						}
					}
					if (unique) {
						sameNamedMethods.add(method);
					}
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getBinaryName(javax.lang.model.element.TypeElement)
	 */
	@Override
	public Name getBinaryName(TypeElement type) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getConstantExpression(java.lang.Object)
	 */
	@Override
	public String getConstantExpression(Object value) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getDocComment(javax.lang.model.element.Element)
	 */
	@Override
	public String getDocComment(Element e) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getElementValuesWithDefaults(javax.lang.model.element.AnnotationMirror)
	 */
	@Override
	public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(
			AnnotationMirror a) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getName(java.lang.CharSequence)
	 */
	@Override
	public Name getName(CharSequence cs) {
		return new NameImpl(cs);
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getPackageElement(java.lang.CharSequence)
	 */
	@Override
	public PackageElement getPackageElement(CharSequence name) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getPackageOf(javax.lang.model.element.Element)
	 */
	@Override
	public PackageElement getPackageOf(Element type) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#getTypeElement(java.lang.CharSequence)
	 */
	@Override
	public TypeElement getTypeElement(CharSequence name) {
		LookupEnvironment le = _env.getLookupEnvironment();
		//TODO: do this the right way - this is a hack to test if it works
		String qname = name.toString();
		String parts[] = qname.split("\\."); //$NON-NLS-1$
		int length = parts.length;
		char[][] compoundName = new char[length][];
		for (int i = 0; i < length; i++) {
			compoundName[i] = parts[i].toCharArray();
		}
		ReferenceBinding binding = le.getType(compoundName);
		if (binding == null) {
			return null;
		}
		return new TypeElementImpl(binding);
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#hides(javax.lang.model.element.Element, javax.lang.model.element.Element)
	 */
	@Override
	public boolean hides(Element hider, Element hidden) {
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#isDeprecated(javax.lang.model.element.Element)
	 */
	@Override
	public boolean isDeprecated(Element e) {
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#overrides(javax.lang.model.element.ExecutableElement, javax.lang.model.element.ExecutableElement, javax.lang.model.element.TypeElement)
	 */
	@Override
	public boolean overrides(ExecutableElement overrider, ExecutableElement overridden,
			TypeElement type) {
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see javax.lang.model.util.Elements#printElements(java.io.Writer, javax.lang.model.element.Element[])
	 */
	@Override
	public void printElements(Writer w, Element... elements) {
		// TODO Auto-generated method stub

	}

}
