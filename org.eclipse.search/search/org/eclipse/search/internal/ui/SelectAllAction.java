/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.search.internal.ui;

import org.eclipse.jface.action.Action;

import org.eclipse.ui.PlatformUI;
 
/**
 * This action selects all entries currently showing in view.
 */
class SelectAllAction extends Action {

	private SearchResultViewer fViewer;

	/**
	 * Creates the action.
	 */
	SelectAllAction(SearchResultViewer viewer) {
		super("selectAll"); //$NON-NLS-1$
		setText(SearchMessages.SelectAllAction_label); 
		setToolTipText(SearchMessages.SelectAllAction_tooltip); 
		PlatformUI.getWorkbench().getHelpSystem().setHelp(this, ISearchHelpContextIds.SELECT_ALL_ACTION);
		fViewer= viewer;
	}

	/**
	 * Selects all resources in the view.
	 */
	public void run() {
		fViewer.getTable().selectAll();
		// force viewer selection change
		fViewer.setSelection(fViewer.getSelection());
	}
}
