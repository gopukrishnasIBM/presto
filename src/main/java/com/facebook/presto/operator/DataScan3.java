package com.facebook.presto.operator;

import com.facebook.presto.block.BlockStream;
import com.facebook.presto.block.Cursor;
import com.facebook.presto.block.MaskedValueBlock;
import com.facebook.presto.TupleInfo;
import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockCursor;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;

import java.util.Iterator;
import java.util.List;

public class DataScan3
        implements BlockStream<Block>
{
    private final BlockStream<? extends Block> source;
    private final BlockStream<? extends Block> positions;

    public DataScan3(BlockStream<? extends Block> source, BlockStream<? extends Block> positions)
    {
        this.source = source;
        this.positions = positions;
    }

    @Override
    public TupleInfo getTupleInfo()
    {
        return source.getTupleInfo();
    }

    @Override
    public Iterator<Block> iterator()
    {
        return new AbstractIterator<Block>()
        {
            Iterator<? extends Block> valueBlocks = source.iterator();
            Iterator<? extends Block> positionBlocks = positions.iterator();
            Block currentPositionBlock = positionBlocks.next();

            @Override
            protected Block computeNext()
            {
                while (valueBlocks.hasNext()) {
                    Block currentValueBlock = valueBlocks.next();

                    // advance current position block to value block
                    while (currentPositionBlock.getRange().getEnd() < currentValueBlock.getRange().getStart()) {
                        if (!positionBlocks.hasNext()) {
                            endOfData();
                            return null;
                        }
                        currentPositionBlock = positionBlocks.next();
                    }

                    // get all position blocks that overlap with the value block
                    ImmutableList.Builder<Block> positionsForCurrentBlock = ImmutableList.builder();
                    while (positionBlocks.hasNext() && currentPositionBlock.getRange().getEnd() < currentValueBlock.getRange().getEnd()) {
                        positionsForCurrentBlock.add(currentPositionBlock);
                        currentPositionBlock = positionBlocks.next();
                    }

                    // if current position block overlaps with value block, add it
                    if (currentPositionBlock.getRange().overlaps(currentValueBlock.getRange()))  {
                        positionsForCurrentBlock.add(currentPositionBlock);
                    }

                    // if the value block and the position blocks have and positions in common, output a block
                    List<Long> validPositions = getValidPositions(currentValueBlock, positionsForCurrentBlock.build());
                    if (!validPositions.isEmpty()) {
                        return new MaskedValueBlock(currentValueBlock, validPositions);
                    }
                }
                endOfData();
                return null;
            }

            private List<Long> getValidPositions(Block currentValueBlock, List<Block> positionsForCurrentBlock)
            {
                ImmutableList.Builder<Long> validPositions = ImmutableList.builder();

                BlockCursor valueCursor = currentValueBlock.blockCursor();
                valueCursor.advanceNextPosition();

                for (Block positionBlock : positionsForCurrentBlock) {
                    BlockCursor positionCursor = positionBlock.blockCursor();
                    while (positionCursor.advanceNextPosition()) {
                        long nextPosition = positionCursor.getPosition();
                        if (nextPosition > valueCursor.getRange().getEnd()) {
                            break;
                        }
                        if (nextPosition > valueCursor.getPosition()) {
                            valueCursor.advanceToPosition(nextPosition);
                        }
                        if (valueCursor.getPosition() == nextPosition) {
                            validPositions.add(nextPosition);
                        }
                    }
                }
                return validPositions.build();
            }
        };
    }

    @Override
    public Cursor cursor()
    {
        return new ValueCursor(getTupleInfo(), iterator());
    }
}
