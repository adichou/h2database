/*
 * Copyright 2004-2009 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.index;

import java.sql.SQLException;
import org.h2.constant.ErrorCode;
import org.h2.constant.SysProperties;
import org.h2.message.Message;
import org.h2.result.SearchRow;
import org.h2.store.Data;
import org.h2.store.DataPage;

/**
 * A b-tree leaf page that contains index data.
 * Format:
 * <ul><li>0-3: parent page id (0 for root)
 * </li><li>4-4: page type
 * </li><li>5-8: table id
 * </li><li>9-10: entry count
 * </li><li>11-: list of key / offset pairs (4 bytes key, 2 bytes offset)
 * </li><li>data
 * </li></ul>
 */
class PageBtreeLeaf extends PageBtree {

    private static final int OFFSET_LENGTH = 2;
    private static final int OFFSET_START = 11;

    PageBtreeLeaf(PageBtreeIndex index, int pageId, int parentPageId, Data data) {
        super(index, pageId, parentPageId, data);
        start = OFFSET_START;
    }

    void read() throws SQLException {
        data.setPos(4);
        int type = data.readByte();
        onlyPosition = (type & Page.FLAG_LAST) == 0;
        int tableId = data.readInt();
        if (tableId != index.getId()) {
            throw Message.getSQLException(ErrorCode.FILE_CORRUPTED_1,
                    "page:" + getPos() + " expected table:" + index.getId() +
                    "got:" + tableId);
        }
        entryCount = data.readShortInt();
        offsets = new int[entryCount];
        rows = new SearchRow[entryCount];
        for (int i = 0; i < entryCount; i++) {
            offsets[i] = data.readShortInt();
        }
        start = data.length();
    }

    int addRowTry(SearchRow row) throws SQLException {
        int rowLength = index.getRowSize(data, row, onlyPosition);
        int pageSize = index.getPageStore().getPageSize();
        int last = entryCount == 0 ? pageSize : offsets[entryCount - 1];
        if (last - rowLength < start + OFFSET_LENGTH) {
            if (entryCount > 1) {
                return entryCount / 2;
            }
            onlyPosition = true;
            // change the offsets (now storing only positions)
            int o = pageSize;
            for (int i = 0; i < entryCount; i++) {
                o -= index.getRowSize(data, getRow(i), onlyPosition);
                offsets[i] = o;
            }
            last = entryCount == 0 ? pageSize : offsets[entryCount - 1];
            rowLength = index.getRowSize(data, row, onlyPosition);
            if (SysProperties.CHECK && last - rowLength < start + OFFSET_LENGTH) {
                throw Message.throwInternalError();
            }
        }
        written = false;
        int offset = last - rowLength;
        int[] newOffsets = new int[entryCount + 1];
        SearchRow[] newRows = new SearchRow[entryCount + 1];
        int x;
        if (entryCount == 0) {
            x = 0;
        } else {
            readAllRows();
            x = find(row, false, true, true);
            System.arraycopy(offsets, 0, newOffsets, 0, x);
            System.arraycopy(rows, 0, newRows, 0, x);
            if (x < entryCount) {
                for (int j = x; j < entryCount; j++) {
                    newOffsets[j + 1] = offsets[j] - rowLength;
                }
                offset = (x == 0 ? pageSize : offsets[x - 1]) - rowLength;
                System.arraycopy(rows, x, newRows, x + 1, entryCount - x);
            }
        }
        entryCount++;
        start += OFFSET_LENGTH;
        newOffsets[x] = offset;
        newRows[x] = row;
        offsets = newOffsets;
        rows = newRows;
        index.getPageStore().updateRecord(this, true, data);
        return -1;
    }

    private void removeRow(int i) throws SQLException {
        readAllRows();
        entryCount--;
        written = false;
        if (entryCount <= 0) {
            Message.throwInternalError();
        }
        int[] newOffsets = new int[entryCount];
        SearchRow[] newRows = new SearchRow[entryCount];
        System.arraycopy(offsets, 0, newOffsets, 0, i);
        System.arraycopy(rows, 0, newRows, 0, i);
        int startNext = i > 0 ? offsets[i - 1] : index.getPageStore().getPageSize();
        int rowLength = startNext - offsets[i];
        for (int j = i; j < entryCount; j++) {
            newOffsets[j] = offsets[j + 1] + rowLength;
        }
        System.arraycopy(rows, i + 1, newRows, i, entryCount - i);
        start -= OFFSET_LENGTH;
        offsets = newOffsets;
        rows = newRows;
    }

    int getEntryCount() {
        return entryCount;
    }

    PageBtree split(int splitPoint) throws SQLException {
        int newPageId = index.getPageStore().allocatePage();
        PageBtreeLeaf p2 = new PageBtreeLeaf(index, newPageId, parentPageId, index.getPageStore().createData());
        for (int i = splitPoint; i < entryCount;) {
            p2.addRowTry(getRow(splitPoint));
            removeRow(splitPoint);
        }
        return p2;
    }

    PageBtreeLeaf getFirstLeaf() {
        return this;
    }

    PageBtreeLeaf getLastLeaf() {
        return this;
    }

    SearchRow remove(SearchRow row) throws SQLException {
        int at = find(row, false, false, true);
        SearchRow delete = getRow(at);
        if (index.compareRows(row, delete) != 0 || delete.getPos() != row.getPos()) {
            throw Message.getSQLException(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1, index.getSQL() + ": " + row);
        }
        if (entryCount == 1) {
            // the page is now empty
            return row;
        }
        removeRow(at);
        index.getPageStore().updateRecord(this, true, data);
        if (at == entryCount) {
            // the last row changed
            return getRow(at - 1);
        }
        // the last row didn't change
        return null;
    }

    void freeChildren() {
        // nothing to do
    }

    int getRowCount() {
        return entryCount;
    }

    void setRowCountStored(int rowCount) {
        // ignore
    }

    public int getByteCount(DataPage dummy) {
        return index.getPageStore().getPageSize();
    }

    public void write(DataPage buff) throws SQLException {
        write();
        index.getPageStore().writePage(getPos(), data);
    }

    private void write() throws SQLException {
        if (written) {
            return;
        }
        readAllRows();
        data.reset();
        data.writeInt(parentPageId);
        data.writeByte((byte) (Page.TYPE_BTREE_LEAF | (onlyPosition ? 0 : Page.FLAG_LAST)));
        data.writeInt(index.getId());
        data.writeShortInt(entryCount);
        for (int i = 0; i < entryCount; i++) {
            data.writeShortInt(offsets[i]);
        }
        for (int i = 0; i < entryCount; i++) {
            index.writeRow(data, offsets[i], rows[i], onlyPosition);
        }
        written = true;
    }

    void find(PageBtreeCursor cursor, SearchRow first, boolean bigger) throws SQLException {
        int i = find(first, bigger, false, false);
        if (i > entryCount) {
            if (parentPageId == Page.ROOT) {
                return;
            }
            PageBtreeNode next = (PageBtreeNode) index.getPage(parentPageId);
            next.find(cursor, first, bigger);
            return;
        }
        cursor.setCurrent(this, i);
    }

    void last(PageBtreeCursor cursor) {
        cursor.setCurrent(this, entryCount - 1);
    }

    void remapChildren() {
        // nothing to do
    }

    /**
     * Set the cursor to the first row of the next page.
     *
     * @param cursor the cursor
     */
    void nextPage(PageBtreeCursor cursor) throws SQLException {
        if (parentPageId == Page.ROOT) {
            cursor.setCurrent(null, 0);
            return;
        }
        PageBtreeNode next = (PageBtreeNode) index.getPage(parentPageId);
        next.nextPage(cursor, getPos());
    }

    /**
     * Set the cursor to the last row of the previous page.
     *
     * @param cursor the cursor
     */
    void previousPage(PageBtreeCursor cursor) throws SQLException {
        if (parentPageId == Page.ROOT) {
            cursor.setCurrent(null, 0);
            return;
        }
        PageBtreeNode next = (PageBtreeNode) index.getPage(parentPageId);
        next.previousPage(cursor, getPos());
    }

    public String toString() {
        return "page[" + getPos() + "] b-tree leaf table:" + index.getId() + " entries:" + entryCount;
    }

}