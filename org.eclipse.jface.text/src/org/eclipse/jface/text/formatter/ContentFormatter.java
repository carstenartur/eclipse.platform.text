/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jface.text.formatter;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.ChildDocumentManager;
import org.eclipse.jface.text.DefaultPositionUpdater;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.IPositionUpdater;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.TypedPosition;


/**
 * Standard implementation of <code>IContentFormatter</code>.
 * The formatter supports two operation modi: partition aware and
 * partition unaware. <p>
 * In the partition aware mode, the formatter determines the 
 * partitioning of the document region to be formatted. For each 
 * partition it determines all document positions  which are affected 
 * when text changes are applied to the partition. Those which overlap
 * with the partition are remembered as character positions. These
 * character positions are passed over to the formatting strategy
 * registered for the partition's content type. The formatting strategy
 * returns a string containing the formatted document partition as well
 * as the adapted character positions. The formatted partition replaces
 * the old content of the partition. The remembered document postions 
 * are updated with the adapted character positions. In addition, all
 * other document positions are accordingly adapted to the formatting 
 * changes.<p>
 * In the partition unaware mode, the document's partitioning is ignored
 * and the document is considered consisting of only one partition of 
 * the content type <code>IDocument.DEFAULT_CONTENT_TYPE</code>. The 
 * formatting process is similar to the partition aware mode, with the 
 * exception of having only one partition.<p>
 * Usually, clients instantiate this class and configure it before using it.
 *
 * @see IContentFormatter
 * @see IDocument
 * @see ITypedRegion
 * @see Position
 */
public class ContentFormatter implements IContentFormatter, IContentFormatterExtension, IContentFormatterExtension2 {
		
	/**
	 * Defines a reference to either the offset or the end offset of
	 * a particular position.
	 */
	static class PositionReference implements Comparable {
		
		/** The referenced position */
		protected Position fPosition;
		/** The reference to either the offset or the end offset */
		protected boolean fRefersToOffset;
		/** The original category of the referenced position */
		protected String fCategory;
		
		/**
		 * Creates a new position reference.
		 * 
		 * @param position the position to be referenced
		 * @param refersToOffset <code>true</code> if position offset should be referenced
		 * @param category the categpry the given position belongs to
		 */
		protected PositionReference(Position position, boolean refersToOffset, String category) {
			fPosition= position;
			fRefersToOffset= refersToOffset;
			fCategory= category;
		}
		
		/**
		 * Returns the offset of the referenced position.
		 * 
		 * @return the offset of the referenced position
		 */
		protected int getOffset() {
			return fPosition.getOffset();
		}
		
		/**
		 * Manipulates the offset of the referenced position.
		 * 
		 * @param offset the new offset of the referenced position
		 */
		protected void setOffset(int offset) {
			fPosition.setOffset(offset);
		}
		
		/**
		 * Returns the length of the referenced position.
		 * 
		 * @return the length of the referenced position
		 */
		protected int getLength() {
			return fPosition.getLength();
		}
		
		/**
		 * Manipulates the length of the referenced position.
		 * 
		 * @param the new length of the referenced position
		 */
		protected void setLength(int length) {
			fPosition.setLength(length);
		}
		
		/**
		 * Returns whether this reference points to the offset or endoffset
		 * of the references position.
		 * 
		 * @return <code>true</code> if the offset of the position is referenced, <code>false</code> otherwise
		 */
		protected boolean refersToOffset() {
			return fRefersToOffset;
		}
		
		/**
		 * Returns the category of the referenced position.
		 * 
		 * @return the category of the referenced position
		 */
		protected String getCategory() {
			return fCategory;
		}
		
		/**
		 * Returns the referenced position.
		 * 
		 * @return the referenced position
		 */
		protected Position getPosition() {
			return fPosition;
		}
		
		/**
		 * Returns the referenced character position
		 * 
		 * @return the referenced character position
		 */
		protected int getCharacterPosition() {
			if (fRefersToOffset)
				return getOffset();
			return getOffset() + getLength();
		}
		
		/*
		 * @see Comparable#compareTo(Object)
		 */
		public int compareTo(Object obj) {
			
			if (obj instanceof PositionReference) {
				PositionReference r= (PositionReference) obj;
				return getCharacterPosition() - r.getCharacterPosition();
			}
			
			throw new ClassCastException();
		}
	}
	
	/**
	 * The position updater used to update the remembered partitions.
	 *
	 * @see IPositionUpdater
	 * @see DefaultPositionUpdater
	 */
	class NonDeletingPositionUpdater extends DefaultPositionUpdater {
		
		/**
		 * Creates a new updater for the given category.
		 * 
		 * @param category the category
		 */
		protected NonDeletingPositionUpdater(String category) {
			super(category);
		}
		
		/*
		 * @see DefaultPositionUpdater#notDeleted()
		 */
		protected boolean notDeleted() {
			return true;
		}
	}
	
	/**
	 * The position updater which runs as first updater on the document's positions.
	 * Used to remove all affected positions from their categories to avoid them
	 * from being regularily updated.
	 * 
	 * @see IPositionUpdater
	 */
	class RemoveAffectedPositions implements IPositionUpdater {
		/**
		 * @see IPositionUpdater#update(DocumentEvent)
		 */
		public void update(DocumentEvent event) {
			removeAffectedPositions(event.getDocument());
		}	
	}
	
	/**
	 * The position updater which runs as last updater on the document's positions.
	 * Used to update all affected positions and adding them back to their
	 * original categories.
	 * 
	 * @see IPositionUpdater
	 */
	class UpdateAffectedPositions implements IPositionUpdater {
		
		/** The affected positions */
		private int[] fPositions;
		/** The offset */
		private int fOffset;
		
		/**
		 * Creates a new updater.
		 * 
		 * @param positions the affected positions
		 * @param offset the offset
		 */
		public UpdateAffectedPositions(int[] positions, int offset) {
			fPositions= positions;
			fOffset= offset;
		}
		
		/*
		 * @see IPositionUpdater#update(DocumentEvent)
		 */
		public void update(DocumentEvent event) {
			updateAffectedPositions(event.getDocument(), fPositions, fOffset);
		}	
	}
	
	
	/** Internal position category used for the formatter partitioning */
	private final static String PARTITIONING= "__formatter_partitioning"; //$NON-NLS-1$
	
	/** The map of <code>IFormattingStrategy</code> objects */
	private Map fStrategies;
	/** The indicator of whether the formatter operates in partition aware mode or not */
	private boolean fIsPartitionAware= true;
	
	/** The partition information managing document position categories */
	private String[] fPartitionManagingCategories;
	/** The list of references to offset and end offset of all overlapping positions */
	private List fOverlappingPositionReferences;
	/** Position updater used for partitioning positions */
	private IPositionUpdater fPartitioningUpdater;
	/**
	 * The document partitioning used by this formatter.
	 * @since 3.0
	 */
	private String fPartitioning;
	/** 
	 * The document this formatter works on.
	 * @since 3.0
	 */
	private IDocument fDocument;
	/**
	 * The external partition managing categories.
	 * @since 3.0
	 */
	private String[] fExternalPartitonManagingCategories;
	/**
	 * Indicates whether <code>fPartitionManagingCategories</code> must be computed.
	 * @since 3.0
	 */
	private boolean fNeedsComputation= true;
	/**
	 * Formatting context to use while formatting
	 * @since 3.0
	 */
	private IFormattingContext fFormattingContext= null;
	/**
	 * Queue of position arrays used during formatting.
	 * @since 3.0
	 */
	private final LinkedList fPositions= new LinkedList();
	
	/**
	 * Creates a new content formatter. The content formatter operates by default
	 * in the partition-aware mode. There are no preconfigured formatting strategies.
	 * Will use the default document partitioning if not further configured.
	 */
	public ContentFormatter() {
		fPartitioning= IDocumentExtension3.DEFAULT_PARTITIONING;
	}
		
	/**
	 * Registers a strategy for a particular content type. If there is already a strategy
	 * registered for this type, the new strategy is registered instead of the old one.
	 * If the given content type is <code>null</code> the given strategy is registered for
	 * all content types as is called only once per formatting session.
	 *
	 * @param strategy the formatting strategy to register, or <code>null</code> to remove an existing one
	 * @param contentType the content type under which to register, or <code>null</code> for all content types
	 */
	public void setFormattingStrategy(IFormattingStrategy strategy, String contentType) {
		
		Assert.isNotNull(contentType);
			
		if (fStrategies == null)
			fStrategies= new HashMap();
			
		if (strategy == null)
			fStrategies.remove(contentType);
		else
			fStrategies.put(contentType, strategy);
	}
	
	/**
	 * Informs this content formatter about the names of those position categories
	 * which are used to manage the document's partitioning information and thus should
	 * be ignored when this formatter updates positions.
	 *
	 * @param categories the categories to be ignored
	 * @deprecated incompatible with an open set of document partitionings. The provided information is only used
	 * 		if this formatter can not compute the partition managing position categories.
	 */
	public void setPartitionManagingPositionCategories(String[] categories) {
		fExternalPartitonManagingCategories= categories;
	}
	
	/**
	 * Sets the document partitioning to be used by this formatter.
	 * 
	 * @param partitioning the document partitioning
	 * @since 3.0
	 */
	public void setDocumentPartitioning(String partitioning) {
		fPartitioning= partitioning;
	}
	
	/*
	 * @see org.eclipse.jface.text.formatter.IContentFormatterExtension#getDocumentPartitioning()
	 * @since 3.0
	 */
	public String getDocumentPartitioning() {
		return fPartitioning;
	}

	/**
	 * Sets the formatter's operation mode.
	 * 
	 * @param enable indicates whether the formatting process should be partition ware
	 */
	public void enablePartitionAwareFormatting(boolean enable) {
		fIsPartitionAware= enable;
	}
	
	/*
	 * @see IContentFormatter#getFormattingStrategy(String)
	 */
	public IFormattingStrategy getFormattingStrategy(String contentType) {
		
		Assert.isNotNull(contentType);
		
		if (fStrategies == null)
			return null;
								
		return (IFormattingStrategy) fStrategies.get(contentType);
	}
	
	/*
	 * @see IContentFormatter#format(IDocument, IRegion)
	 */
	public void format(IDocument document, IRegion region) {
		
		fDocument= document;
		fNeedsComputation= true;
		fFormattingContext= null;
		
		try {

			final int offset= region.getOffset();
			final int length= region.getLength();

			if (fIsPartitionAware)
				formatPartitions(offset, length);
			else
				formatRegion(offset, length, IDocument.DEFAULT_CONTENT_TYPE);

		} finally {
			fFormattingContext= null;
			fNeedsComputation= true;
			fDocument= null;
		}
	}

	/*
	 * @see org.eclipse.jface.text.formatter.IContentFormatterExtension2#format(org.eclipse.jface.text.IDocument, org.eclipse.jface.text.formatter.IFormattingContext)
	 */
	public void format(IDocument document, IFormattingContext context) {

		fNeedsComputation= true;
		fDocument= document;
		fFormattingContext= context;
		fPositions.clear();

		try {

			final Boolean all= (Boolean) context.getProperty(FormattingContextProperties.CONTEXT_DOCUMENT);
			if (all == null || !all.booleanValue()) {

				final TypedPosition partition= (TypedPosition) context.getProperty(FormattingContextProperties.CONTEXT_PARTITION);
				final IRegion region= (IRegion) context.getProperty(FormattingContextProperties.CONTEXT_REGION);

				if (partition != null) {
					formatRegion(partition.getOffset(), partition.getLength(), partition.getType());
				} else if (region != null) {

					final int offset= region.getOffset();
					final int length= region.getLength();

					final ITypedRegion[] regions= TextUtilities.computePartitioning(fDocument, fPartitioning, offset, length);
					if (regions.length > 1) {

						try {
							formatRegion(offset, length, IDocument.DEFAULT_CONTENT_TYPE);
						} finally {
							formatPartitions(offset, length);
						}
					} else
						formatRegion(offset, length, regions[0].getType());
				}
			} else {

				try {
					formatRegion(0, fDocument.getLength(), IDocument.DEFAULT_CONTENT_TYPE);
				} finally {
					formatPartitions(0, fDocument.getLength());
				}
			}
		} catch (BadLocationException x) {
			// Should not happen
		} finally {

			fNeedsComputation= true;
			fFormattingContext= null;
			fDocument= null;
			fPositions.clear();
		}
	}

	/**
	 * Determines the partitioning of the given region of the document.
	 * Informs the formatting strategies of each partition about the start,
	 * the process, and the termination of the formatting session.
	 * 
	 * @param offset The offset of the region to be formatted
	 * @param length The length of the region to be formatted
	 */
	private void formatPartitions(int offset, int length) {

		addPartitioningUpdater();
		
		try {

			final TypedPosition[] ranges= getPartitioning(offset, length);

			if (ranges != null) {

				start(ranges, getIndentation(offset));
				format(ranges);
				stop(ranges);
			}

		} catch (BadLocationException exception) {
			// Can not happen
		}
		removePartitioningUpdater();
	}
	
	/**
	 * Formats the given region with the strategy registered for the default
	 * content type. The strategy is informed about the start, the process, and
	 * the termination of the formatting session.
	 * 
	 * @param offset The offset of the region
	 * @param length The length of the region
	 * @param type The type of the region
	 */
	private void formatRegion(final int offset, final int length, final String type) {

		final IFormattingStrategy strategy= getFormattingStrategy(type);
		if (strategy != null) {

			final TypedPosition partition= new TypedPosition(offset, length, type);

			formatterStarts(strategy, partition, getIndentation(offset));
			format(strategy, partition);
			strategy.formatterStops();
		}
	}
	
	/**
	 * Fires the formatter starts event for the indicated formatting strategy.
	 * 
	 * @param strategy Strategy to fire the event for
	 * @param region Region where the strategy is supposed to format
	 * @param indentation Indentation of the formatting region
	 */
	private void formatterStarts(final IFormattingStrategy strategy, final TypedPosition region, final String indentation) {

		if (fFormattingContext != null && strategy instanceof IFormattingStrategyExtension) {

			final IFormattingStrategyExtension extension= (IFormattingStrategyExtension)strategy;
			final int[] positions= getAffectedPositions(region.getOffset(), region.getLength());

			fPositions.addLast(positions);

			fFormattingContext.setProperty(FormattingContextProperties.CONTEXT_INDENTATION, indentation);
			fFormattingContext.setProperty(FormattingContextProperties.CONTEXT_PARTITION, region);
			fFormattingContext.setProperty(FormattingContextProperties.CONTEXT_POSITIONS, positions);

			extension.formatterStarts(fFormattingContext);
		} else
			strategy.formatterStarts(indentation);
	}
	
	/**
	 * Returns the partitioning of the given region of the specified document.
	 * As one partition after the other will be formatted and formatting will 
	 * probably change the length of the formatted partition, it must be kept 
	 * track of the modifications in order to submit the correct partition to all 
	 * formatting strategies. For this, all partitions are remembered as positions
	 * in a dedicated position category.
	 *
	 * @param offset Offset of the region for which the partitioning must be determined
	 * @param length Length of the region for which the partitioning must be determined
	 * @return the partitioning of the specified region
	 * @exception BadLocationException of region is invalid in the document
	 */
	private TypedPosition[] getPartitioning(int offset, int length) throws BadLocationException {
		
		ITypedRegion[] regions= TextUtilities.computePartitioning(fDocument, fPartitioning, offset, length);
		TypedPosition[] positions= new TypedPosition[regions.length];
		
		for (int i= 0; i < regions.length; i++) {
			positions[i]= new TypedPosition(regions[i]);
			try {
				fDocument.addPosition(PARTITIONING, positions[i]);
			} catch (BadPositionCategoryException x) {
				// should not happen
			}
		}
		
		return positions;
	}
	
	/**
	 * Fires <code>formatterStarts</code> to all formatter strategies
	 * which will be involved in the forthcoming formatting process.
	 * 
	 * @param regions the partitioning of the document to be formatted
	 * @param indentation the initial indentation
	 */
	private void start(TypedPosition[] regions, String indentation) {

		String type= null;
		TypedPosition region= null;

		for (int i= regions.length - 1; i >= 0; i--) {

			region= regions[i];
			type= region.getType();

			if (!type.equals(IDocument.DEFAULT_CONTENT_TYPE)) {

				final IFormattingStrategy strategy= getFormattingStrategy(type);
				if (strategy != null)
					formatterStarts(strategy, region, indentation);
			}
		}
	}
	
	/**
	 * Formats one partition after the other using the formatter strategy registered for
	 * the partition's content type.
	 *
	 * @param regions the partitioning of the document region to be formatted
	 */
	private void format(TypedPosition[] regions) {

		String type= null;
		TypedPosition region= null;

		for (int i= regions.length - 1; i >= 0; i--) {

			region= regions[i];
			type= region.getType();

			if (!type.equals(IDocument.DEFAULT_CONTENT_TYPE)) {

				final IFormattingStrategy strategy= getFormattingStrategy(type);
				if (strategy != null) {

					if (fFormattingContext != null && strategy instanceof IFormattingStrategyExtension) {

						final IFormattingStrategyExtension extension= (IFormattingStrategyExtension)strategy;
						extension.format();

					} else
						format(strategy, region);
				}
			}
		}
	}
	
	/**
	 * Formats the given region of the document using the specified formatting
	 * strategy. In order to maintain positions correctly, first all affected 
	 * positions determined, after all document listeners have been informed about
	 * the upcoming change, the affected positions are removed to avoid that they
	 * are regularily updated. After all position updaters have run, the affected
	 * positions are updated with the formatter's information and added back to 
	 * their categories, right before the first document listener is informed about
	 * that a change happend.
	 * 
	 * @param strategy the strategy to be used
	 * @param region the region to be formatted
	 */
	private void format(IFormattingStrategy strategy, TypedPosition region) {

		if (fFormattingContext != null && strategy instanceof IFormattingStrategyExtension) {

			final int[] positions= (int[])fFormattingContext.getProperty(FormattingContextProperties.CONTEXT_POSITIONS);

			IPositionUpdater first= new RemoveAffectedPositions();
			fDocument.insertPositionUpdater(first, 0);
			IPositionUpdater last= new UpdateAffectedPositions(positions, region.getOffset());
			fDocument.addPositionUpdater(last);

			final IFormattingStrategyExtension extension= (IFormattingStrategyExtension)strategy;
			extension.format();

			fDocument.removePositionUpdater(first);
			fDocument.removePositionUpdater(last);

		} else {

			try {

				final int offset= region.getOffset();
				int length= region.getLength();

				String content= fDocument.get(offset, length);
				final int[] positions= getAffectedPositions(offset, length);
				String formatted= strategy.format(content, isLineStart(offset), getIndentation(offset), positions);

				if (formatted != null && !formatted.equals(content)) {

					IPositionUpdater first= new RemoveAffectedPositions();
					fDocument.insertPositionUpdater(first, 0);
					IPositionUpdater last= new UpdateAffectedPositions(positions, offset);
					fDocument.addPositionUpdater(last);

					fDocument.replace(offset, length, formatted);

					fDocument.removePositionUpdater(first);
					fDocument.removePositionUpdater(last);
				}

			} catch (BadLocationException x) {
				// should not happen
			}
		}
	}
	
	/**
	 * Fires <code>formatterStops</code> to all formatter strategies which were
	 * involved in the formatting process which is about to terminate.
	 *
	 * @param regions the partitioning of the document which has been formatted
	 */
	private void stop(TypedPosition[] regions) {

		String type= null;
		for (int i= regions.length - 1; i >= 0; i--) {

			type= regions[i].getType();
			if (!type.equals(IDocument.DEFAULT_CONTENT_TYPE)) {

				final IFormattingStrategy strategy= getFormattingStrategy(type);
				if (strategy != null)
					strategy.formatterStops();
			}
		}
	}
		
	/**
	 * Installs those updaters which the formatter needs to keep track of the partitions.
	 */
	private void addPartitioningUpdater() {
		fPartitioningUpdater= new NonDeletingPositionUpdater(PARTITIONING);
		fDocument.addPositionCategory(PARTITIONING);
		fDocument.addPositionUpdater(fPartitioningUpdater);
	}
	
	/**
	 * Removes the formatter's internal position updater and category.
	 */
	private void removePartitioningUpdater() {
		
		try {
						
			fDocument.removePositionUpdater(fPartitioningUpdater);
			fDocument.removePositionCategory(PARTITIONING);
			fPartitioningUpdater= null;
			
		} catch (BadPositionCategoryException x) {
			// should not happen
		}
	}
	
	/**
	 * Returns the partition managing position categories for the formatted document.
	 * 
	 * @return the position managing position categories
	 * @since 3.0
	 */
	private String[] getPartitionManagingCategories() {
		if (fNeedsComputation) {
			fNeedsComputation= false;
			fPartitionManagingCategories= TextUtilities.computePartitionManagingCategories(fDocument);
			if (fPartitionManagingCategories == null)
				fPartitionManagingCategories= fExternalPartitonManagingCategories;
		}
		return fPartitionManagingCategories;
	}
	
	/**
	 * Determines whether the given document position category should be ignored
	 * by this formatter's position updating.
	 *
	 * @param category the category to check
	 * @return <code>true</code> if the category should be ignored, <code>false</code> otherwise
	 */
	private boolean ignoreCategory(String category) {
		
		if (PARTITIONING.equals(category))
			return true;
		
		String[] categories= getPartitionManagingCategories();
		if (categories != null) {
			for (int i= 0; i < categories.length; i++) {
				if (categories[i].equals(category))
					return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Determines all embracing, overlapping, and follow up positions 
	 * for the given region of the document.
	 *
	 * @param offset the offset of the document region to be formatted
	 * @param length the length of the document to be formatted
	 */
	private void determinePositionsToUpdate(int offset, int length) {
		
		String[] categories= fDocument.getPositionCategories();
		if (categories != null) {
			for (int i= 0; i < categories.length; i++) {
				
				if (ignoreCategory(categories[i]))
					continue;
					
				try {
					
					Position[] positions= fDocument.getPositions(categories[i]);
					
					for (int j= 0; j < positions.length; j++) {
						
						Position p= positions[j];
						if (p.overlapsWith(offset, length)) {

							if (offset < p.getOffset())
								fOverlappingPositionReferences.add(new PositionReference(p, true, categories[i]));
							
							if (p.getOffset() + p.getLength() < offset + length)
								fOverlappingPositionReferences.add(new PositionReference(p, false, categories[i]));
						}
					}
					
				} catch (BadPositionCategoryException x) {
					// can not happen
				}
			}
		}
	}
	
	/**
	 * Returns all offset and the end offset of all positions overlapping with the 
	 * specified document range.
	 *
	 * @param offset the offset of the document region to be formatted
	 * @param length the length of the document to be formatted
	 * @return all character positions of the interleaving positions
	 */
	private int[] getAffectedPositions(int offset, int length) {
		
		fOverlappingPositionReferences= new ArrayList();
		
		determinePositionsToUpdate(offset, length);
		
		Collections.sort(fOverlappingPositionReferences);
		
		int[] positions= new int[fOverlappingPositionReferences.size()];
		for (int i= 0; i < positions.length; i++) {
			PositionReference r= (PositionReference) fOverlappingPositionReferences.get(i);
			positions[i]= r.getCharacterPosition() - offset;
		}
		
		return positions;
	}
	
	/**
	 * Removes the affected positions from their categories to avoid
	 * that they are invalidly updated.
	 * 
	 * @param document the document 
	 */
	private void removeAffectedPositions(IDocument document) {
		if (fOverlappingPositionReferences != null) {
			int size= fOverlappingPositionReferences.size();
			for (int i= 0; i < size; i++) {
				PositionReference r= (PositionReference)fOverlappingPositionReferences.get(i);
				try {
					document.removePosition(r.getCategory(), r.getPosition());
				} catch (BadPositionCategoryException x) {
					// can not happen
				}
			}
		}	
	}
	
	/**
	 * Updates all the overlapping positions. Note, all other positions are
	 * automatically updated by their document position updaters.
	 *
	 * @param document the document to has been formatted
	 * @param positions the adapted character positions to be used to update the document positions
	 * @param offset the offset of the document region that has been formatted
	 */
	protected void updateAffectedPositions(IDocument document, int[] positions, int offset) {
		
		if (document != fDocument)
			return;
		
		if (fOverlappingPositionReferences == null || positions.length == 0)
			return;
		
		for (int i= 0; i < positions.length; i++) {
			
			PositionReference r= (PositionReference) fOverlappingPositionReferences.get(i);
			
			if (r.refersToOffset())
				r.setOffset(offset + positions[i]);
			else
				r.setLength((offset + positions[i]) - r.getOffset());
			
			Position p= r.getPosition();
			String category= r.getCategory();
			if (!document.containsPosition(category, p.offset, p.length)) {
				try {
					if (positionAboutToBeAdded(document, category, p))
						document.addPosition(r.getCategory(), p);
				} catch (BadPositionCategoryException x) {
					// can not happen
				} catch (BadLocationException x) {
					// should not happen
				}
			}	
			
		}
		
		fOverlappingPositionReferences= null;
	}

	/**
	 * The given position is about to be added to the given position category of the given document. <p>
	 * This default implementation enacts the same rule as the TextViewer, i.e. if the position is used for 
	 * managing slave documents it is ensured that the slave document starts at a line offset.
	 * 
	 * @param document the document
	 * @param category the position categroy
	 * @param position the position that will be added
	 * @return <code>true</code> if the position can be added, <code>false</code> if it should be ignored
	 */
	protected boolean positionAboutToBeAdded(IDocument document, String category, Position position) {
		if (ChildDocumentManager.CHILDDOCUMENTS.equals(category)) {
			/* 
			 * We assume child document offsets to be at the beginning
			 * of a line. Because the formatter might have moved the
			 * position to be somewhere in the middle of a line we patch it here. 
			 */
			try {
				int lineOffset= document.getLineInformationOfOffset(position.offset).getOffset();
				position.setLength(position.length + position.offset - lineOffset);
				position.setOffset(lineOffset);
			} catch (BadLocationException x) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Returns the indentation of the line of the given offset.
	 *
	 * @param offset the offset
	 * @return the indentation of the line of the offset
	 */
	private String getIndentation(int offset) {
		
		try {
			int start= fDocument.getLineOfOffset(offset);
			start= fDocument.getLineOffset(start);
			
			int end= start;
			char c= fDocument.getChar(end);
			while ('\t' == c || ' ' == c)
				c= fDocument.getChar(++end);
				
			return fDocument.get(start, end - start);
		} catch (BadLocationException x) {
		}
		
		return ""; //$NON-NLS-1$
	}
	
	/**
	 * Determines whether the offset is the beginning of a line in the given document.
	 *
	 * @param offset the offset
	 * @return <code>true</code> if offset is the beginning of a line
	 * @exception BadLocationException if offset is invalid in document
	 */
	private boolean isLineStart(int offset) throws BadLocationException {
		int start= fDocument.getLineOfOffset(offset);
		start= fDocument.getLineOffset(start);
		return (start == offset);
	}	
}
