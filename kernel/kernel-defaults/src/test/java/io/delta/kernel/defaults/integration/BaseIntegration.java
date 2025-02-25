/*
 * Copyright (2023) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.defaults.integration;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.hadoop.conf.Configuration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.client.TableClient;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.types.*;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;

import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import static io.delta.kernel.internal.util.Utils.singletonCloseableIterator;

import io.delta.kernel.defaults.client.DefaultTableClient;
import io.delta.kernel.defaults.utils.DefaultKernelTestUtils;

/**
 * Base class containing utility method to write integration tests that read data from
 * Delta tables using the Kernel APIs.
 */
public abstract class BaseIntegration {
    protected TableClient tableClient = DefaultTableClient.create(
        new Configuration() {
            {
                // Set the batch sizes to small so that we get to test the multiple batch scenarios.
                set("delta.kernel.default.parquet.reader.batch-size", "2");
                set("delta.kernel.default.json.reader.batch-size", "2");
            }
        });

    protected Table table(String path) throws Exception {
        return Table.forPath(tableClient, path);
    }

    protected Snapshot snapshot(String path) throws Exception {
        return table(path).getLatestSnapshot(tableClient);
    }

    protected List<ColumnarBatch> readSnapshot(StructType readSchema, Snapshot snapshot)
        throws Exception {
        Scan scan = snapshot.getScanBuilder(tableClient)
            .withReadSchema(tableClient, readSchema)
            .build();

        Row scanState = scan.getScanState(tableClient);
        CloseableIterator<FilteredColumnarBatch> scanFileIter = scan.getScanFiles(tableClient);

        return readScanFiles(scanState, scanFileIter);
    }

    protected List<ColumnarBatch> readScanFiles(
        Row scanState,
        CloseableIterator<FilteredColumnarBatch> scanFilesBatchIter) throws Exception {
        List<ColumnarBatch> dataBatches = new ArrayList<>();
        try {
            StructType physicalReadSchema =
                ScanStateRow.getPhysicalDataReadSchema(tableClient, scanState);
            while (scanFilesBatchIter.hasNext()) {
                FilteredColumnarBatch scanFilesBatch = scanFilesBatchIter.next();
                try (CloseableIterator<Row> scanFileRows = scanFilesBatch.getRows()) {
                    while (scanFileRows.hasNext()) {
                        Row scanFileRow = scanFileRows.next();
                        FileStatus fileStatus = InternalScanFileUtils.getAddFileStatus(scanFileRow);
                        CloseableIterator<ColumnarBatch> physicalDataIter =
                            tableClient.getParquetHandler()
                                .readParquetFiles(
                                    singletonCloseableIterator(fileStatus),
                                    physicalReadSchema,
                                    Optional.empty());
                        try (CloseableIterator<FilteredColumnarBatch> transformedData =
                                 Scan.transformPhysicalData(
                                    tableClient,
                                    scanState,
                                    scanFileRow,
                                    physicalDataIter)) {
                            while (transformedData.hasNext()) {
                                FilteredColumnarBatch filteredData = transformedData.next();
                                assertFalse(filteredData.getSelectionVector().isPresent());
                                dataBatches.add(filteredData.getData());
                            }
                        }
                    }
                }
            }
        } finally {
            scanFilesBatchIter.close();
        }

        return dataBatches;
    }

    /**
     * Remove unsupported top level delta types in Kernel from the schema. Unsupported data types
     * include `TIMESTAMP`.
     */
    protected StructType removeUnsupportedType(StructType schema) {
        List<StructField> filterList =
            schema.fields().stream()
                .filter(
                    field -> !(field.getDataType() instanceof TimestampType)
                ).collect(Collectors.toList());

        return new StructType(filterList);
    }

    protected void compareEqualUnorderd(ColumnarBatch expDataBatch,
        List<ColumnarBatch> actDataBatches) {
        Set<Integer> expDataRowsMatched = new HashSet<>();
        for (int actDataBatchIdx = 0; actDataBatchIdx < actDataBatches.size(); actDataBatchIdx++) {
            ColumnarBatch actDataBatch = actDataBatches.get(actDataBatchIdx);

            assertEquals(expDataBatch.getSchema(), actDataBatch.getSchema());

            for (int actRowIdx = 0; actRowIdx < actDataBatch.getSize(); actRowIdx++) {
                boolean matched = false;
                for (int expRowIdx = 0; expRowIdx < expDataBatch.getSize(); expRowIdx++) {
                    // If the row is already matched by another record, don't match again
                    if (expDataRowsMatched.contains(expRowIdx)) {
                        continue;
                    }

                    matched = compareRows(expDataBatch, expRowIdx, actDataBatch, actRowIdx);
                    if (matched) {
                        expDataRowsMatched.add(expRowIdx);
                        break;
                    }
                }
                assertTrue("Actual data contain a row that is not expected", matched);
            }
        }

        assertEquals(
            "An expected row is not present in the actual data output",
            expDataBatch.getSize(),
            expDataRowsMatched.size());
    }

    protected boolean compareRows(
        ColumnarBatch expDataBatch,
        int expRowId,
        ColumnarBatch actDataBatch,
        int actRowId) {
        StructType readSchema = expDataBatch.getSchema();

        for (int fieldId = 0; fieldId < readSchema.length(); fieldId++) {
            DataType fieldDataType = readSchema.at(fieldId).getDataType();

            ColumnVector expDataVector = expDataBatch.getColumnVector(fieldId);
            ColumnVector actDataVector = actDataBatch.getColumnVector(fieldId);

            Object expObject = DefaultKernelTestUtils.getValueAsObject(expDataVector, expRowId);
            Object actObject = DefaultKernelTestUtils.getValueAsObject(actDataVector, actRowId);
            boolean matched = compareObjects(fieldDataType, expObject, actObject);
            if (!matched) {
                return false;
            }
        }

        return true;
    }

    protected boolean compareRows(Row exp, Row act) {
        assertEquals(exp.getSchema(), act.getSchema());
        for (int fieldId = 0; fieldId < exp.getSchema().length(); fieldId++) {
            DataType fileDataType = exp.getSchema().at(fieldId).getDataType();

            Object expObject = DefaultKernelTestUtils.getValueAsObject(exp, fieldId);
            Object actObject = DefaultKernelTestUtils.getValueAsObject(act, fieldId);
            boolean matched = compareObjects(fileDataType, expObject, actObject);
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    protected <T> boolean compareArrays(ArrayType dataType, List<T> exp, List<T> act) {
        assertEquals(exp.size(), act.size());
        for (int i = 0; i < exp.size(); i++) {
            boolean matched = compareObjects(dataType.getElementType(), exp.get(i), act.get(i));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    protected <K, V> boolean compareMaps(MapType dataType, Map<K, V> exp, Map<K, V> act) {
        assertEquals(exp.size(), act.size());
        Set<Map.Entry<K, V>> expEntrySet = exp.entrySet();
        for (Map.Entry<K, V> expEntry : expEntrySet) {
            // TODO: this doesn't work for key types that don't have equals/hashCode implemented.
            K expKey = expEntry.getKey();
            V expValue = expEntry.getValue();
            V actValue = act.get(expKey);
            boolean matched = compareObjects(dataType.getValueType(), expValue, actValue);
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    protected boolean compareObjects(DataType dataType, Object exp, Object act) {
        boolean matched = Objects.deepEquals(exp, act);
        if (dataType instanceof StructType) {
            matched = compareRows((Row) exp, (Row) act);
        } else if (dataType instanceof ArrayType) {
            matched = compareArrays((ArrayType) dataType, (List) exp, (List) act);
        } else if (dataType instanceof MapType) {
            matched = compareMaps((MapType) dataType, (Map) exp, (Map) act);
        }
        return matched;
    }
}
