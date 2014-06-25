package org.fxmisc.flowless;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.stream.Stream;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.IndexRange;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

public class VirtualFlow<T, C extends Node> extends Region {
    // Children of a VirtualFlow are cells. All children are unmanaged.
    // Children correspond to a sublist of items. Not all children are
    // visible. Visible children form a continuous subrange of all children.
    // Invisible children have CSS applied, but are not sized and positioned.


    public static <T, C extends Node> VirtualFlow<T, C> createHorizontal(
            ObservableList<T> items, CellFactory<T, C> cellFactory) {
        return new VirtualFlow<>(items, cellFactory, new HorizontalFlowMetrics());
    }

    public static <T, C extends Node> VirtualFlow<T, C> createVertical(
            ObservableList<T> items, CellFactory<T, C> cellFactory) {
        return new VirtualFlow<>(items, cellFactory, new VerticalFlowMetrics());
    }

    private final ScrollBar hbar;
    private final ScrollBar vbar;
    private final VirtualFlowContent<T, C> content;


    private VirtualFlow(ObservableList<T> items, CellFactory<T, C> cellFactory, Metrics metrics) {
        this.content = new VirtualFlowContent<>(items, cellFactory, metrics);

        // create scrollbars
        hbar = new ScrollBar();
        vbar = new ScrollBar();
        vbar.setOrientation(Orientation.VERTICAL);

        // scrollbar ranges
        hbar.setMin(0);
        vbar.setMin(0);
        hbar.maxProperty().bind(metrics.widthEstimateProperty(content));
        vbar.maxProperty().bind(metrics.heightEstimateProperty(content));

        // scrollbar increments
        setupUnitIncrement(hbar);
        setupUnitIncrement(vbar);
        hbar.blockIncrementProperty().bind(hbar.visibleAmountProperty());
        vbar.blockIncrementProperty().bind(vbar.visibleAmountProperty());

        // scrollbar positions
        hbar.setValue(metrics.getHorizontalPosition(content));
        vbar.setValue(metrics.getVerticalPosition(content));
        metrics.horizontalPositionProperty(content).addListener(
                obs -> hbar.setValue(metrics.getHorizontalPosition(content)));
        metrics.verticalPositionProperty(content).addListener(
                obs -> vbar.setValue(metrics.getVerticalPosition(content)));

        // scroll content by scrollbars
        hbar.valueProperty().addListener((obs, old, pos) ->
                metrics.setHorizontalPosition(content, pos.doubleValue()));
        vbar.valueProperty().addListener((obs, old, pos) ->
                metrics.setVerticalPosition(content, pos.doubleValue()));

        // scroll content by mouse scroll
        this.addEventHandler(ScrollEvent.SCROLL, se -> {
            double dx = se.getDeltaX();
            double dy = se.getDeltaY();
            metrics.scrollVertically(content, dy);
            metrics.scrollHorizontally(content, dx);
            se.consume();
        });

        DoubleBinding layoutWidth = Bindings.createDoubleBinding(
                () -> getLayoutBounds().getWidth(),
                layoutBoundsProperty());
        DoubleBinding layoutHeight = Bindings.createDoubleBinding(
                () -> getLayoutBounds().getHeight(),
                layoutBoundsProperty());

        // scrollbar visibility
        hbar.visibleProperty().bind(Bindings.greaterThan(
                metrics.widthEstimateProperty(content),
                layoutWidth));
        vbar.visibleProperty().bind(Bindings.greaterThan(
                metrics.heightEstimateProperty(content),
                layoutHeight));

        hbar.visibleProperty().addListener(obs -> requestLayout());
        vbar.visibleProperty().addListener(obs -> requestLayout());

        getChildren().addAll(content, hbar, vbar);
    }

    @Override
    public Orientation getContentBias() {
        return content.getContentBias();
    }

    @Override
    public double computePrefWidth(double height) {
        return content.prefWidth(height);
    }

    @Override
    public double computePrefHeight(double width) {
        return content.prefHeight(width);
    }

    @Override
    public double computeMinWidth(double height) {
        return content.minWidth(height);
    }

    @Override
    public double computeMinHeight(double width) {
        return content.minHeight(width);
    }

    @Override
    public double computeMaxWidth(double height) {
        return content.maxWidth(height);
    }

    @Override
    public double computeMaxHeight(double width) {
        return content.maxHeight(width);
    }

    @Override
    protected void layoutChildren() {
        // allow 3 iterations:
        // - the first might result in need of one scrollbar
        // - the second might result in need of the other scrollbar,
        //   as a result of limited space due to the first one
        // - the third iteration should lead to a fixed point
        layoutChildren(3);
    }

    private void layoutChildren(int limit) {
        double layoutWidth = getLayoutBounds().getWidth();
        double layoutHeight = getLayoutBounds().getHeight();
        boolean vbarVisible = vbar.isVisible();
        boolean hbarVisible = hbar.isVisible();
        double vbarWidth = vbarVisible ? vbar.prefWidth(-1) : 0;
        double hbarHeight = hbarVisible ? hbar.prefHeight(-1) : 0;

        double w = layoutWidth - vbarWidth;
        double h = layoutHeight - hbarHeight;

        content.resizeRelocate(0, 0, w, h);

        if(vbar.isVisible() != vbarVisible || hbar.isVisible() != hbarVisible) {
            // the need for scrollbars changed, start over
            if(limit > 1) {
                layoutChildren(limit - 1);
                return;
            } else {
                // layout didn't settle after 3 iterations
            }
        }

        hbar.setVisibleAmount(w);
        vbar.setVisibleAmount(h);

        if(vbarVisible) {
            vbar.resizeRelocate(layoutWidth - vbarWidth, 0, vbarWidth, h);
        }

        if(hbarVisible) {
            hbar.resizeRelocate(0, layoutHeight - hbarHeight, w, hbarHeight);
        }
    }

    private static void setupUnitIncrement(ScrollBar bar) {
        bar.unitIncrementProperty().bind(new DoubleBinding() {
            { bind(bar.maxProperty(), bar.visibleAmountProperty()); }

            @Override
            protected double computeValue() {
                double max = bar.getMax();
                double visible = bar.getVisibleAmount();
                return max > visible
                        ? 13 / (max - visible) * max
                        : 0;
            }
        });
    }
}


class VirtualFlowContent<T, C extends Node> extends Region {
    private final List<T> items;
    private final List<C> cells;
    private final CellFactory<T, C> cellFactory;
    private final Metrics metrics;
    private final BreadthTracker breadthTracker;

    private final IntegerProperty prefCellCount = new SimpleIntegerProperty(20);

    private final Queue<C> cellPool = new LinkedList<>();

    private double visibleLength = 0; // total length of all visible cells
    private int renderedFrom = 0; // index of the first item that has a cell
    private Optional<IndexRange> hole = Optional.empty();

    // offset of the content in breadth axis, <= 0
    private double breadthOffset = 0;

    private final DoubleBinding totalBreadthEstimate;
    public ObservableDoubleValue totalBreadthEstimateProperty() {
        return totalBreadthEstimate;
    }

    private final DoubleBinding totalLengthEstimate;
    public ObservableDoubleValue totalLengthEstimateProperty() {
        return totalLengthEstimate;
    }

    private final DoubleBinding breadthPositionEstimate;
    public ObservableDoubleValue breadthPositionEstimateProperty() {
        return breadthPositionEstimate;
    }

    private final DoubleBinding lengthOffsetEstimate;

    private final DoubleBinding lengthPositionEstimate;
    public ObservableDoubleValue lengthPositionEstimateProperty() {
        return lengthPositionEstimate;
    }

    VirtualFlowContent(ObservableList<T> items, CellFactory<T, C> cellFactory, Metrics metrics) {
        this.items = items;
        this.cellFactory = cellFactory;
        this.metrics = metrics;
        this.breadthTracker = new BreadthTracker(items.size());

        @SuppressWarnings("unchecked")
        ObservableList<C> cells = (ObservableList<C>) getChildren();
        this.cells = cells;

        Rectangle clipRect = new Rectangle();
        setClip(clipRect);

        layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            clipRect.setWidth(newBounds.getWidth());
            clipRect.setHeight(newBounds.getHeight());
            layoutBoundsChanged(oldBounds, newBounds);
        });

        items.addListener((ListChangeListener<? super T>) ch -> {
            while(ch.next()) {
                int pos = ch.getFrom();
                int removedSize;
                int addedSize;
                if(ch.wasPermutated()) {
                    addedSize = removedSize = ch.getTo() - pos;
                } else {
                    removedSize = ch.getRemovedSize();
                    addedSize = ch.getAddedSize();
                }
                itemsReplaced(pos, removedSize, addedSize);
            }
        });


        // set up bindings

        totalBreadthEstimate = new DoubleBinding() {
            @Override
            protected double computeValue() {
                return maxKnownBreadth();
            }
        };

        totalLengthEstimate = new DoubleBinding() {
            @Override
            protected double computeValue() {
                return hasVisibleCells()
                        ? visibleLength / visibleCells().count() * items.size()
                        : 0;
            }
        };

        breadthPositionEstimate = new DoubleBinding() {
            @Override
            protected double computeValue() {
                if(items.isEmpty()) {
                    return 0;
                }

                double total = maxKnownBreadth();
                if(total <= breadth()) {
                    return 0;
                }

                return breadthPixelsToPosition(-breadthOffset);
            }
        };

        lengthOffsetEstimate = new DoubleBinding() {
            @Override
            protected double computeValue() {
                if(items.isEmpty()) {
                    return 0;
                }

                double total = totalLengthEstimate.get();
                if(total <= length()) {
                    return 0;
                }

                double avgLen = total / items.size();
                double beforeVisible = firstVisibleRange().getStart() * avgLen;
                return beforeVisible - visibleCellsMinY();
            }
        };

        lengthPositionEstimate = new DoubleBinding() {
            { bind(lengthOffsetEstimate); }

            @Override
            protected double computeValue() {
                return pixelsToPosition(lengthOffsetEstimate.get());
            }
        };
    }

    @Override
    protected void layoutChildren() {
        // do nothing
    }

    @Override
    protected final double computePrefWidth(double height) {
        switch(getContentBias()) {
            case HORIZONTAL: // vertical flow
                return computePrefBreadth();
            case VERTICAL: // horizontal flow
                return computePrefLength(height);
            default:
                throw new AssertionError("Unreachable code");
        }
    }

    @Override
    protected final double computePrefHeight(double width) {
        switch(getContentBias()) {
            case HORIZONTAL: // vertical flow
                return computePrefLength(width);
            case VERTICAL: // horizontal flow
                return computePrefBreadth();
            default:
                throw new AssertionError("Unreachable code");
        }
    }

    private double computePrefBreadth() {
        // take maximum of all rendered cells,
        // but first ensure there are at least 10 rendered cells
        ensureRenderedCells(10);
        return cells.stream()
                .mapToDouble(metrics::prefBreadth)
                .reduce(0, (a, b) -> Math.max(a, b));
    }

    private double computePrefLength(double breadth) {
        int n = prefCellCount.get();
        ensureRenderedCells(n);
        return cells.stream().limit(n)
                .mapToDouble(cell -> metrics.prefLength(cell, breadth))
                .sum();
    }

    @Override
    public final Orientation getContentBias() {
        return metrics.getContentBias();
    }

    protected final void setLengthPosition(double pos) {
        setLengthOffset(positionToPixels(pos));
    }

    protected final void setBreadthPosition(double pos) {
        setBreadthOffset(breadthPositionToPixels(pos));
    }

    protected final void scrollLength(double deltaLength) {
        setLengthOffset(lengthOffsetEstimate.get() - deltaLength);
    }

    protected final void scrollBreadth(double deltaBreadth) {
        setBreadthOffset(breadthOffset - deltaBreadth);
    }

    private void ensureRenderedCells(int n) {
        for(int i = cells.size(); i < n; ++i) {
            if(hole.isPresent()) {
                render(hole.get().getStart());
            } else if(renderedFrom > 0) {
                render(renderedFrom - 1);
            } else if(renderedFrom + cells.size() < items.size()) {
                render(renderedFrom + cells.size());
            } else {
                break;
            }
        }
    }

    private C renderInitial(int index) {
        if(!cells.isEmpty()) {
            throw new IllegalStateException("There are some rendered cells already");
        }

        renderedFrom = index;
        visibleLength = 0;

        return render(index, 0);
    }

    private C render(int index, int childInsertionPos) {
        T item = items.get(index);
        C cell = createCell(index, item);
        cell.setVisible(false);
        cells.add(childInsertionPos, cell);
        cell.applyCss();
        return cell;
    }

    private C render(int index) {
        int renderedTo = renderedFrom + cells.size() + hole.map(IndexRange::getLength).orElse(0);
        if(index < renderedFrom - 1) {
            throw new IllegalArgumentException("Cannot render " + index + ". Rendered cells start at " + renderedFrom);
        } else if(index == renderedFrom - 1) {
            C cell = render(index, 0);
            renderedFrom -= 1;
            return cell;
        } else if(index == renderedTo) {
            return render(index, cells.size());
        } else if(index > renderedTo) {
            throw new IllegalArgumentException("Cannot render " + index + ". Rendered cells end at " + renderedTo);
        } else if(hole.isPresent()) {
            IndexRange hole = this.hole.get();
            if(index < hole.getStart()) {
                return cells.get(index - renderedFrom);
            } else if(index >= hole.getEnd()) {
                return cells.get(index - renderedFrom - hole.getLength());
            } else if(index == hole.getStart()) {
                C cell = render(index, index - renderedFrom);
                this.hole = hole.getLength() == 1
                        ? Optional.empty()
                        : Optional.of(new IndexRange(index + 1, hole.getEnd()));
                return cell;
            } else if(index == hole.getEnd() - 1) {
                C cell = render(index, hole.getStart() - renderedFrom);
                this.hole = Optional.of(new IndexRange(hole.getStart(), hole.getEnd() - 1));
                return cell;
            } else {
                throw new IllegalArgumentException("Cannot render " + index + " inside hole " + hole);
            }
        } else {
            return cells.get(index - renderedFrom);
        }
    }

    private C createCell(int index, T item) {
        C cell;
        C cachedCell = getFromPool();
        if(cachedCell != null) {
            cell = cellFactory.createCell(index, item, cachedCell);
            if(cell != cachedCell) {
                cellFactory.disposeCell(cachedCell);
            }
        } else {
            cell = cellFactory.createCell(index, item);
        }
        cell.setManaged(false);
        return cell;
    }

    private C getFromPool() {
        return cellPool.poll();
    }

    private void addToPool(C cell) {
        if(cell.isVisible()) {
            visibleLength -= metrics.length(cell);
        }
        cellFactory.resetCell(cell);
        cellPool.add(cell);
    }

    private void cullFrom(int pos) {
        if(hole.isPresent()) {
            IndexRange hole = this.hole.get();
            if(pos >= hole.getEnd()) {
                dropCellsFrom(pos - hole.getLength() - renderedFrom);
            } else if(pos > hole.getStart()) {
                dropCellsFrom(hole.getStart() - renderedFrom);
                this.hole = Optional.of(new IndexRange(hole.getStart(), pos));
            } else {
                dropCellsFrom(pos - renderedFrom);
                this.hole = Optional.empty();
            }
        } else {
            dropCellsFrom(pos - renderedFrom);
        }
    }

    private void cullBefore(int pos) {
        if(hole.isPresent()) {
            IndexRange hole = this.hole.get();
            if(pos <= hole.getStart()) {
                dropCellsBefore(pos - renderedFrom);
            } else if(pos < hole.getEnd()) {
                dropCellsBefore(hole.getStart() - renderedFrom);
                this.hole = Optional.of(new IndexRange(pos, hole.getEnd()));
            } else {
                dropCellsBefore(pos - hole.getLength() - renderedFrom);
                this.hole = Optional.empty();
            }
        } else {
            dropCellsBefore(pos - renderedFrom);
        }

        renderedFrom = pos;
    }

    private void dropCellsFrom(int cellIdx) {
        dropCellRange(cellIdx, cells.size());
    }

    private void dropCellsBefore(int cellIdx) {
        dropCellRange(0, cellIdx);
    }

    private void dropCellRange(int from, int to) {
        List<C> toDrop = cells.subList(from, to);
        toDrop.forEach(this::addToPool);
        toDrop.clear();
    }

    private void layoutBoundsChanged(Bounds oldBounds, Bounds newBounds) {
        double oldBreadth = metrics.breadth(oldBounds);
        double newBreadth = metrics.breadth(newBounds);
        double minBreadth = maxKnownBreadth();
        double breadth = Math.max(minBreadth, newBreadth);

        // adjust breadth of visible cells
        if(oldBreadth != newBreadth) {
            if(oldBreadth <= minBreadth && newBreadth <= minBreadth) {
                // do nothing
            } else {
                resizeVisibleCells(breadth);
            }
        }

        if(breadth + breadthOffset < newBreadth) { // empty space on the right
            shiftVisibleCellsByBreadth(newBreadth - (breadth + breadthOffset));
        }

        // fill current screen
        fillViewport(0);

        totalBreadthEstimate.invalidate();
        totalLengthEstimate.invalidate();
        breadthPositionEstimate.invalidate();
        lengthOffsetEstimate.invalidate();
    }

    private void itemsReplaced(int pos, int removedSize, int addedSize) {
        if(hole.isPresent()) {
            throw new IllegalStateException("change in items before hole was closed");
        }

        breadthTracker.itemsReplaced(pos, removedSize, addedSize);

        if(pos >= renderedFrom + cells.size()) {
            // does not affect any cells, do nothing
        } else if(pos + removedSize <= renderedFrom) {
            // change before rendered cells, just update indices
            int delta = addedSize - removedSize;
            renderedFrom += delta;
            for(int i = 0; i < cells.size(); ++i) {
                cellFactory.updateIndex(cells.get(i), renderedFrom + i);
            }
        } else if(pos > renderedFrom && pos + removedSize < renderedFrom + cells.size()) {
            // change within rendered cells,
            // at least one cell retained on both sides
            dropCellRange(pos - renderedFrom, pos + removedSize - renderedFrom);
            for(int i = pos + renderedFrom; i < cells.size(); ++i) {
                cellFactory.updateIndex(cells.get(i), pos + addedSize + i);
            }
            if(addedSize > 0) {
                // creating a hole in rendered cells
                hole = Optional.of(new IndexRange(pos, pos + addedSize));
            }
        } else if(pos > renderedFrom) {
            dropCellsFrom(pos - renderedFrom);
        } else if(pos + removedSize >= renderedFrom + cells.size()) {
            // all rendered items removed
            dropCellsFrom(0);
            renderedFrom = 0;
            visibleLength = 0;
        } else {
            dropCellsBefore(pos + removedSize - renderedFrom);
            renderedFrom = pos + addedSize;
            for(int i = 0; i < cells.size(); ++i) {
                cellFactory.updateIndex(cells.get(i), pos + i);
            }
        }

        fillViewport(pos);

        totalBreadthEstimate.invalidate();
        totalLengthEstimate.invalidate();
        breadthPositionEstimate.invalidate();
        lengthOffsetEstimate.invalidate();
    }

    private void fillViewport(int ifEmptyStartWith) {
        if(!hasVisibleCells()) {
            if(hole.isPresent()) {
                // There is a hole in rendered cells.
                // Place its first item at the start of the viewport.
                shrinkHoleFromLeft(0.0);
            } else if(!cells.isEmpty()) {
                // There are at least some rendered cells.
                // Place the first one at 0.
                placeAt(renderedFrom, cells.get(0), 0.0);
            } else if(!items.isEmpty()) {
                // use the hint
                int idx = ifEmptyStartWith < 0 ? 0
                        : ifEmptyStartWith >= items.size() ? items.size() - 1
                        : ifEmptyStartWith;
                placeInitialAtStart(idx);
            } else {
                return;
            }
        }

        fillViewport();
    }

    private void fillViewport() {
        if(!hasVisibleCells()) {
            throw new IllegalStateException("need a visible cell to start from");
        }

        double breadth = Math.max(maxKnownBreadth(), breadth());

        boolean repeat = true;
        while(repeat) {
            fillViewportOnce();

            if(maxKnownBreadth() > breadth) { // broader cell encountered
                breadth = maxKnownBreadth();
                resizeVisibleCells(breadth);
            } else {
                repeat = false;
            }
        }

        // cull, but first eliminate the hole
        if(hole.isPresent()) {
            IndexRange hole = this.hole.get();
            if(hole.getStart() > renderedFrom) {
                C cellBeforeHole = cells.get(hole.getStart() - 1 - renderedFrom);
                if(!cellBeforeHole.isVisible() || metrics.maxY(cellBeforeHole) <= 0) {
                    cullBefore(hole.getEnd());
                } else {
                    cullFrom(hole.getStart());
                }
            } else {
                cullBefore(hole.getEnd());
            }
        }
        cullBeforeViewport();
        cullAfterViewport();
    }

    private void cullBeforeViewport() {
        if(hole.isPresent()) {
            throw new IllegalStateException("unexpected hole");
        }

        // find first in the viewport
        int i = 0;
        for(; i < cells.size(); ++i) {
            C cell = cells.get(i);
            if(cell.isVisible() && metrics.maxY(cell) > 0) {
                break;
            }
        }

        cullBefore(renderedFrom + i);
    }

    private void cullAfterViewport() {
        if(hole.isPresent()) {
            throw new IllegalStateException("unexpected hole");
        }

        // find first after the viewport
        int i = 0;
        for(; i < cells.size(); ++i) {
            C cell = cells.get(i);
            if(!cell.isVisible() || metrics.minY(cell) >= length()) {
                break;
            }
        }

        cullFrom(renderedFrom + i);
    }

    private void fillViewportOnce() {
        // expand the visible range
        IndexRange visibleRange = firstVisibleRange();
        int firstVisible = visibleRange.getStart();
        int lastVisible = visibleRange.getEnd() - 1;

        // fill backward until 0 is covered
        double minY = metrics.minY(getVisibleCell(firstVisible));
        while(minY > 0) {
            if(firstVisible > 0) {
                C cell = placeEndAt(--firstVisible, minY);
                minY = metrics.minY(cell);
            } else {
                shiftVisibleCellsByLength(-minY);
                minY = 0;
            }
        }

        // fill forward until end of viewport is covered
        double length = length();
        double maxY = metrics.maxY(getVisibleCell(lastVisible));
        while(maxY < length) {
            if(lastVisible < items.size() - 1) {
                C cell = placeAt(++lastVisible, maxY);
                maxY = metrics.maxY(cell);
            } else {
                break;
            }
        }

        double leftToFill = length - maxY;
        if(leftToFill > 0) {
            while(-minY < leftToFill && firstVisible > 0) {
                C cell = placeEndAt(--firstVisible, minY);
                minY = metrics.minY(cell);
            }
            double shift = Math.min(-minY, leftToFill);
            shiftVisibleCellsByLength(shift);
        }
    }

    private C getVisibleCell(int itemIdx) {
        if(itemIdx < renderedFrom) {
            throw new IllegalArgumentException("Item " + itemIdx + " is not visible");
        } else if(hole.isPresent()) {
            IndexRange hole = this.hole.get();
            C cell;
            if(itemIdx < hole.getStart()) {
                cell = cells.get(itemIdx - renderedFrom);
            } else if(itemIdx < hole.getEnd()) {
                throw new IllegalArgumentException("Item " + itemIdx + " is not visible");
            } else if(itemIdx < renderedFrom + hole.getLength() + cells.size()) {
                cell = cells.get(itemIdx - hole.getLength() - renderedFrom);
            } else {
                throw new IllegalArgumentException("Item " + itemIdx + " is not visible");
            }
            if(cell.isVisible()) {
                return cell;
            } else {
                throw new IllegalArgumentException("Item " + itemIdx + " is not visible");
            }
        } else if(itemIdx >= renderedFrom + cells.size()) {
            throw new IllegalArgumentException("Item " + itemIdx + " is not visible");
        } else {
            C cell = cells.get(itemIdx - renderedFrom);
            if(cell.isVisible()) {
                return cell;
            } else {
                throw new IllegalArgumentException("Item " + itemIdx + " is not visible");
            }
        }
    }

    private boolean hasVisibleCells() {
        return cells.stream().anyMatch(Node::isVisible);
    }

    private void shrinkHoleFromLeft(double placeAtY) {
        int itemIdx = hole.get().getStart();
        int cellIdx = itemIdx - renderedFrom;
        C cell = render(itemIdx, cellIdx);
        placeAt(itemIdx, cell, placeAtY);
        hole = hole.get().getLength() == 1
                ? Optional.empty()
                : Optional.of(new IndexRange(itemIdx + 1, hole.get().getEnd()));
    }

    private void placeAt(int itemIdx, C cell, double y) {
        double minBreadth = metrics.minBreadth(cell);
        breadthTracker.reportBreadth(itemIdx, minBreadth);
        double breadth = Math.max(maxKnownBreadth(), breadth());
        double length = metrics.prefLength(cell, breadth);
        layoutCell(cell, y, breadth, length);
    }

    private void placeEndAt(int itemIdx, C cell, double endY) {
        double minBreadth = metrics.minBreadth(cell);
        breadthTracker.reportBreadth(itemIdx, minBreadth);
        double breadth = Math.max(maxKnownBreadth(), breadth());
        double length = metrics.prefLength(cell, breadth);
        layoutCell(cell, endY - length, breadth, length);
    }

    private C placeAt(int itemIdx, double y) {
        C cell = render(itemIdx);
        placeAt(itemIdx, cell, y);
        return cell;
    }

    private C placeEndAt(int itemIdx, double endY) {
        C cell = render(itemIdx);
        placeEndAt(itemIdx, cell, endY);
        return cell;
    }

    private void placeInitialAt(int itemIdx, double y) {
        C cell = renderInitial(itemIdx);
        placeAt(itemIdx, cell, y);
    }

    private void placeInitialAtStart(int itemIdx) {
        placeInitialAt(itemIdx, 0.0);
    }

    private void placeInitialAtEnd(int itemIdx) {
        C cell = renderInitial(itemIdx);
        double length = length();
        placeAt(itemIdx, cell, length - metrics.length(cell));
    }

    private void layoutCell(C cell, double l0, double breadth, double length) {
        if(cell.isVisible()) {
            visibleLength -= metrics.length(cell);
        } else {
            cell.setVisible(true);
        }
        visibleLength += length;
        metrics.resizeRelocate(cell, breadthOffset, l0, breadth, length);
    }

    private void shiftVisibleCellsByLength(double shift) {
        visibleCells().forEach(cell -> {
            metrics.relocate(cell, breadthOffset, metrics.minY(cell) + shift);
        });
    }

    private void shiftVisibleCellsByBreadth(double shift) {
        breadthOffset += shift;
        visibleCells().forEach(cell -> {
            metrics.relocate(cell, breadthOffset, metrics.minY(cell));
        });
    }

    private void resizeVisibleCells(double breadth) {
        if(hole.isPresent()) {
            throw new IllegalStateException("unexpected hole in rendered cells");
        }

        double y = visibleCellsMinY();
        for(C cell: cells) {
            if(cell.isVisible()) {
                double length = metrics.prefLength(cell, breadth);
                layoutCell(cell, y, breadth, length);
                y += length;
            }
        }
    }

    private Stream<C> visibleCells() {
        return cells.stream().filter(Node::isVisible);
    }

    public double maxKnownBreadth() {
        return breadthTracker.maxKnownBreadth();
    }

    private double length() {
        return metrics.length(this);
    }

    private double breadth() {
        return metrics.breadth(this);
    }

    private double visibleCellsMinY() {
        return visibleCells().findFirst().map(metrics::minY).orElse(0.0);
    }

    private IndexRange firstVisibleRange() {
        if(cells.isEmpty()) {
            throw new IllegalStateException("no rendered cells");
        }

        if(hole.isPresent()) {
            IndexRange rng = visibleRangeIn(0, hole.get().getStart() - renderedFrom);
            if(rng != null) {
                return new IndexRange(rng.getStart() + renderedFrom, rng.getEnd() + renderedFrom);
            } else if((rng = visibleRangeIn(hole.get().getStart() - renderedFrom, cells.size())) != null) {
                return new IndexRange(rng.getStart() + hole.get().getLength() + renderedFrom, rng.getEnd() + hole.get().getLength() + renderedFrom);
            } else {
                throw new IllegalStateException("no visible cells");
            }
        } else {
            IndexRange rng = visibleRangeIn(0, cells.size());
            if(rng != null) {
                return new IndexRange(rng.getStart() + renderedFrom, rng.getEnd() + renderedFrom);
            } else {
                throw new IllegalStateException("no visible cells");
            }
        }
    }

    private IndexRange visibleRangeIn(int from, int to) {
        int a;
        for(a = from; a < to; ++a) {
            if(cells.get(a).isVisible()) {
                break;
            }
        }
        if(a < to) {
            int b;
            for(b = a + 1; b < to; ++b) {
                if(!cells.get(b).isVisible()) {
                    break;
                }
            }
            return new IndexRange(a, b);
        } else {
            return null;
        }
    }

    private void setLengthOffset(double pixels) {
        double total = totalLengthEstimate.get();
        double length = length();
        double max = Math.max(total - length, 0);
        double current = lengthOffsetEstimate.get();

        if(pixels > max) pixels = max;
        if(pixels < 0) pixels = 0;

        double diff = pixels - current;
        if(Math.abs(diff) < length) { // distance less than one screen
            shiftVisibleCellsByLength(-diff);
            fillViewport(0);
        } else {
            goToY(pixels);
        }

        totalBreadthEstimate.invalidate();
        totalLengthEstimate.invalidate();
        lengthOffsetEstimate.invalidate();
    }

    private void setBreadthOffset(double pixels) {
        double total = totalBreadthEstimate.get();
        double breadth = breadth();
        double max = Math.max(total - breadth, 0);
        double current = -breadthOffset;

        if(pixels > max) pixels = max;
        if(pixels < 0) pixels = 0;

        if(pixels != current) {
            shiftVisibleCellsByBreadth(current - pixels);
            breadthPositionEstimate.invalidate();
        }
    }

    private void goToY(double pixels) {
        if(items.isEmpty()) {
            return;
        }

        // guess the first visible cell and its offset in the viewport
        double total = totalLengthEstimate.get();
        double avgLen = total / items.size();
        if(avgLen == 0) return;
        int first = (int) Math.floor(pixels / avgLen);
        double firstOffset = -(pixels % avgLen);

        // remove all cells
        cullFrom(renderedFrom);

        if(first < items.size()) {
            placeInitialAt(first, firstOffset);
            fillViewport();
        } else {
            placeInitialAtEnd(items.size()-1);
            fillViewport();
        }
    }

    private double pixelsToPosition(double pixels) {
        double total = totalLengthEstimate.get();
        double length = length();
        return total > length
                ? pixels / (total - length) * total
                : 0;
    }

    private double positionToPixels(double pos) {
        double total = totalLengthEstimate.get();
        double length = length();
        return total > 0 && total > length
                ? pos / total * (total - length())
                : 0;
    }

    private double breadthPixelsToPosition(double pixels) {
        double total = totalBreadthEstimate.get();
        double breadth = breadth();
        return total > breadth
                ? pixels / (total - breadth) * total
                : 0;
    }

    private double breadthPositionToPixels(double pos) {
        double total = totalBreadthEstimate.get();
        double breadth = breadth();
        return total > 0 && total > breadth
                ? pos / total * (total - breadth)
                : 0;
    }
}

final class BreadthTracker {
    private final List<Double> breadths; // NaN means not known
    private double maxKnownBreadth = 0; // NaN means needs recomputing

    BreadthTracker(int initSize) {
        breadths = new ArrayList<>(initSize);
        for(int i = 0; i < initSize; ++i) {
            breadths.add(Double.NaN);
        }
    }

    void reportBreadth(int itemIdx, double breadth) {
        breadths.set(itemIdx, breadth);
        if(!Double.isNaN(maxKnownBreadth) && breadth > maxKnownBreadth) {
            maxKnownBreadth = breadth;
        }
    }

    void itemsReplaced(int pos, int removedSize, int addedSize) {
        List<Double> remBreadths = breadths.subList(pos, pos + removedSize);
        for(double b: remBreadths) {
            if(b == maxKnownBreadth) {
                maxKnownBreadth = Double.NaN;
                break;
            }
        }
        remBreadths.clear();
        for(int i = 0; i < addedSize; ++i) {
            remBreadths.add(Double.NaN);
        }
    }

    double maxKnownBreadth() {
        if(Double.isNaN(maxKnownBreadth)) {
            maxKnownBreadth = breadths.stream()
                    .filter(x -> !Double.isNaN(x))
                    .mapToDouble(x -> x)
                    .reduce(0, (a, b) -> Math.max(a, b));
        }
        return maxKnownBreadth;
    }
}

interface Metrics {
    Orientation getContentBias();
    double length(Bounds bounds);
    double breadth(Bounds bounds);
    double minY(Bounds bounds);
    double maxY(Bounds bounds);
    default double length(Node cell) { return length(cell.getLayoutBounds()); }
    default double breadth(Node cell) { return breadth(cell.getLayoutBounds()); }
    default double minY(Node cell) { return minY(cell.getBoundsInParent()); }
    default double maxY(Node cell) { return maxY(cell.getBoundsInParent()); }
    double minBreadth(Node cell);
    double prefBreadth(Node cell);
    double prefLength(Node cell, double breadth);
    void resizeRelocate(Node cell, double b0, double l0, double breadth, double length);
    void relocate(Node cell, double b0, double l0);

    ObservableDoubleValue widthEstimateProperty(VirtualFlowContent<?, ?> content);
    ObservableDoubleValue heightEstimateProperty(VirtualFlowContent<?, ?> content);
    ObservableDoubleValue horizontalPositionProperty(VirtualFlowContent<?, ?> content);
    ObservableDoubleValue verticalPositionProperty(VirtualFlowContent<?, ?> content);
    void setHorizontalPosition(VirtualFlowContent<?, ?> content, double pos);
    void setVerticalPosition(VirtualFlowContent<?, ?> content, double pos);
    void scrollHorizontally(VirtualFlowContent<?, ?> content, double dx);
    void scrollVertically(VirtualFlowContent<?, ?> content, double dy);

    default double getHorizontalPosition(VirtualFlowContent<?, ?> content) {
        return horizontalPositionProperty(content).get();
    }
    default double getVerticalPosition(VirtualFlowContent<?, ?> content) {
        return verticalPositionProperty(content).get();
    }
}

final class HorizontalFlowMetrics implements Metrics {

    @Override
    public Orientation getContentBias() {
        return Orientation.VERTICAL;
    }

    @Override
    public double minBreadth(Node cell) {
        return cell.minHeight(-1);
    }

    @Override
    public double prefBreadth(Node cell) {
        return cell.prefHeight(-1);
    }

    @Override
    public double prefLength(Node cell, double breadth) {
        return cell.prefWidth(breadth);
    }

    @Override
    public double breadth(Bounds bounds) {
        return bounds.getHeight();
    }

    @Override
    public double length(Bounds bounds) {
        return bounds.getWidth();
    }

    @Override
    public double maxY(Bounds bounds) {
        return bounds.getMaxX();
    }

    @Override
    public double minY(Bounds bounds) {
        return bounds.getMinX();
    }

    @Override
    public void resizeRelocate(
            Node cell, double b0, double l0, double breadth, double length) {
        cell.resizeRelocate(l0, b0, length, breadth);
    }

    @Override
    public void relocate(Node cell, double b0, double l0) {
        cell.relocate(l0, b0);
    }

    @Override
    public ObservableDoubleValue widthEstimateProperty(
            VirtualFlowContent<?, ?> content) {
        return content.totalLengthEstimateProperty();
    }

    @Override
    public ObservableDoubleValue heightEstimateProperty(
            VirtualFlowContent<?, ?> content) {
        return content.totalBreadthEstimateProperty();
    }

    @Override
    public ObservableDoubleValue horizontalPositionProperty(
            VirtualFlowContent<?, ?> content) {
        return content.lengthPositionEstimateProperty();
    }

    @Override
    public ObservableDoubleValue verticalPositionProperty(
            VirtualFlowContent<?, ?> content) {
        return content.breadthPositionEstimateProperty();
    }

    @Override
    public void setHorizontalPosition(VirtualFlowContent<?, ?> content,
            double pos) {
        content.setLengthPosition(pos);
    }

    @Override
    public void setVerticalPosition(VirtualFlowContent<?, ?> content, double pos) {
        content.setBreadthPosition(pos);
    }

    @Override
    public void scrollHorizontally(VirtualFlowContent<?, ?> content, double dx) {
        content.scrollLength(dx);
    }

    @Override
    public void scrollVertically(VirtualFlowContent<?, ?> content, double dy) {
        content.scrollBreadth(dy);
    }
}

final class VerticalFlowMetrics implements Metrics {

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public double minBreadth(Node cell) {
        return cell.minWidth(-1);
    }

    @Override
    public double prefBreadth(Node cell) {
        return cell.prefWidth(-1);
    }

    @Override
    public double prefLength(Node cell, double breadth) {
        return cell.prefHeight(breadth);
    }

    @Override
    public double breadth(Bounds bounds) {
        return bounds.getWidth();
    }

    @Override
    public double length(Bounds bounds) {
        return bounds.getHeight();
    }

    @Override
    public double maxY(Bounds bounds) {
        return bounds.getMaxY();
    }

    @Override
    public double minY(Bounds bounds) {
        return bounds.getMinY();
    }

    @Override
    public void resizeRelocate(
            Node cell, double b0, double l0, double breadth, double length) {
        cell.resizeRelocate(b0, l0, breadth, length);
    }

    @Override
    public void relocate(Node cell, double b0, double l0) {
        cell.relocate(b0, l0);
    }

    @Override
    public ObservableDoubleValue widthEstimateProperty(
            VirtualFlowContent<?, ?> content) {
        return content.totalBreadthEstimateProperty();
    }

    @Override
    public ObservableDoubleValue heightEstimateProperty(
            VirtualFlowContent<?, ?> content) {
        return content.totalLengthEstimateProperty();
    }

    @Override
    public ObservableDoubleValue horizontalPositionProperty(
            VirtualFlowContent<?, ?> content) {
        return content.breadthPositionEstimateProperty();
    }

    @Override
    public ObservableDoubleValue verticalPositionProperty(
            VirtualFlowContent<?, ?> content) {
        return content.lengthPositionEstimateProperty();
    }

    @Override
    public void setHorizontalPosition(VirtualFlowContent<?, ?> content,
            double pos) {
        content.setBreadthPosition(pos);
    }

    @Override
    public void setVerticalPosition(VirtualFlowContent<?, ?> content, double pos) {
        content.setLengthPosition(pos);
    }

    @Override
    public void scrollHorizontally(VirtualFlowContent<?, ?> content, double dx) {
        content.scrollBreadth(dx);
    }

    @Override
    public void scrollVertically(VirtualFlowContent<?, ?> content, double dy) {
        content.scrollLength(dy);
    }
}