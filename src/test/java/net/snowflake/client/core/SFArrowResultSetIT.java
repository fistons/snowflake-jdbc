/*
 * Copyright (c) 2012-2019 Snowflake Computing Inc. All rights reserved.
 */
package net.snowflake.client.core;

import net.snowflake.client.jdbc.ArrowResultChunk;
import net.snowflake.client.jdbc.ErrorCode;
import net.snowflake.client.jdbc.SnowflakeResultChunk;
import net.snowflake.client.jdbc.SnowflakeSQLException;
import net.snowflake.client.jdbc.telemetry.NoopTelemetryClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.ipc.ArrowWriter;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static net.snowflake.client.core.ResultUtil.ResultOutput;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class SFArrowResultSetIT
{
  private Random random = new Random();

  /**
   * allocator for arrow
   */
  private BufferAllocator allocator = new RootAllocator(Integer.MAX_VALUE);

  /**
   * temporary folder to store result files
   */
  @Rule
  public TemporaryFolder resultFolder = new TemporaryFolder();

  /**
   * Test the case that all results are returned in first chunk
   */
  @Test
  public void testNoOfflineData() throws Throwable
  {
    List<Field> fieldList = new ArrayList<>();
    Map<String, String> customFieldMeta = new HashMap<>();
    customFieldMeta.put("logicalType", "FIXED");
    customFieldMeta.put("scale", "0");
    FieldType type = new FieldType(false, Types.MinorType.INT.getType(),
                                   null, customFieldMeta);
    fieldList.add(new Field("", type, null));
    Schema schema = new Schema(fieldList);

    Object[][] data = generateData(schema, 1000);
    File file = createArrowFile("testNoOfflineData_0_0_0", schema, data, 10);

    int dataSize = (int) file.length();
    byte[] dataBytes = new byte[dataSize];

    InputStream is = new FileInputStream(file);
    is.read(dataBytes, 0, dataSize);

    ResultOutput resultOutput = new ResultOutput();
    resultOutput.rootAllocator = new RootAllocator(Integer.MAX_VALUE);
    resultOutput.rowsetBase64 = Base64.getEncoder().encodeToString(dataBytes);
    resultOutput.chunkCount = 0;

    SFArrowResultSet resultSet = new SFArrowResultSet(
        resultOutput, new NoopTelemetryClient(), false);

    int i = 0;
    while (resultSet.next())
    {
      int val = resultSet.getInt(1);
      assertThat(val, equalTo(data[0][i]));
      i++;
    }

    // assert that total rowcount is 1000
    assertThat(i, is(1000));
  }

  @Test
  public void testEmptyResultSet() throws Throwable
  {
    ResultOutput resultOutput = new ResultOutput();
    resultOutput.rowsetBase64 =
        Base64.getEncoder().encodeToString("".getBytes(StandardCharsets.UTF_8));
    resultOutput.chunkCount = 0;

    SFArrowResultSet resultSet = new SFArrowResultSet(
        resultOutput, new NoopTelemetryClient(), false);
    assertThat(resultSet.next(), is(false));
    assertThat(resultSet.isLast(), is(false));
    assertThat(resultSet.isAfterLast(), is(true));

    resultOutput.rowsetBase64 = null;
    resultSet = new SFArrowResultSet(
        resultOutput, new NoopTelemetryClient(), false);

    assertThat(resultSet.next(), is(false));
    assertThat(resultSet.isLast(), is(false));
    assertThat(resultSet.isAfterLast(), is(true));
  }

  /**
   * Testing the case that all data comes from chunk downloader
   */
  @Test
  public void testOnlyOfflineData() throws Throwable
  {
    final int colCount = 2;
    final int chunkCount = 10;

    // generate data
    List<Field> fieldList = new ArrayList<>();
    Map<String, String> customFieldMeta = new HashMap<>();
    customFieldMeta.put("logicalType", "FIXED");
    customFieldMeta.put("scale", "0");
    FieldType type = new FieldType(false, Types.MinorType.INT.getType(),
                                   null, customFieldMeta);

    for (int i = 0; i < colCount; i++)
    {
      fieldList.add(new Field("col_" + i, type, null));
    }
    Schema schema = new Schema(fieldList);

    // genreate 10 chunk of data
    List<Object[][]> dataLists = new ArrayList<>();
    List<File> fileLists = new ArrayList<>();
    for (int i = 0; i < chunkCount; i++)
    {
      Object[][] data = generateData(schema, 500);
      File file = createArrowFile("testOnlyOfflineData_" + i, schema, data,
                                  10);
      dataLists.add(data);
      fileLists.add(file);
    }
    ResultOutput resultOutput = new ResultOutput();
    resultOutput.chunkDownloader = new MockChunkDownloader(fileLists);
    resultOutput.chunkCount = chunkCount;


    SFArrowResultSet resultSet = new SFArrowResultSet(
        resultOutput, new NoopTelemetryClient(), false);

    int index = 0;
    while (resultSet.next())
    {
      for (int i = 0; i < colCount; i++)
      {
        int val = resultSet.getInt(i + 1);
        Integer expectedVal =
            (Integer) dataLists.get(index / 500)[i][index % 500];
        assertThat(val, is(expectedVal));
      }
      index++;
    }

    // assert that total rowcount is 5000
    assertThat(index, is(5000));
  }

  /**
   * Testing the case that all data comes from chunk downloader
   */
  @Test
  public void testFirstResponseAndOfflineData() throws Throwable
  {
    final int colCount = 2;
    final int chunkCount = 10;

    // generate data
    List<Field> fieldList = new ArrayList<>();
    Map<String, String> customFieldMeta = new HashMap<>();
    customFieldMeta.put("logicalType", "FIXED");
    customFieldMeta.put("scale", "0");
    FieldType type = new FieldType(false, Types.MinorType.INT.getType(),
                                   null, customFieldMeta);

    for (int i = 0; i < colCount; i++)
    {
      fieldList.add(new Field("col_" + i, type, null));
    }
    Schema schema = new Schema(fieldList);

    // genreate 10 chunk of data
    List<Object[][]> dataLists = new ArrayList<>();
    List<File> fileLists = new ArrayList<>();

    // first chunk set to base64 rowset
    Object[][] firstChunkData = generateData(schema, 500);
    File arrowFile = createArrowFile("testOnlyOfflineData_0", schema,
                                     firstChunkData, 10);

    dataLists.add(firstChunkData);

    int dataSize = (int) arrowFile.length();
    byte[] dataBytes = new byte[dataSize];

    InputStream is = new FileInputStream(arrowFile);
    is.read(dataBytes, 0, dataSize);

    ResultOutput resultOutput = new ResultOutput();
    resultOutput.rowsetBase64 = Base64.getEncoder().encodeToString(dataBytes);
    resultOutput.chunkCount = chunkCount;
    resultOutput.rootAllocator = new RootAllocator(Integer.MAX_VALUE);
    // build chunk downloader
    for (int i = 0; i < chunkCount; i++)
    {
      Object[][] data = generateData(schema, 500);
      File file = createArrowFile("testOnlyOfflineData_" + (i + 1), schema,
                                  data,
                                  10);
      dataLists.add(data);
      fileLists.add(file);
    }
    resultOutput.chunkDownloader = new MockChunkDownloader(fileLists);


    SFArrowResultSet resultSet = new SFArrowResultSet(
        resultOutput, new NoopTelemetryClient(), false);

    int index = 0;
    while (resultSet.next())
    {
      for (int i = 0; i < colCount; i++)
      {
        int val = resultSet.getInt(i + 1);
        Integer expectedVal =
            (Integer) dataLists.get(index / 500)[i][index % 500];
        assertThat(val, is(expectedVal));
      }
      index++;
    }

    // assert that total rowcount is 5500
    assertThat(index, is(5500));
  }

  /**
   * Class to mock chunk downloader. It is just reading data from tmp directory
   * one by one
   */
  private class MockChunkDownloader implements ChunkDownloader
  {
    private List<File> resultFileNames;

    private int currentFileIndex;

    private RootAllocator rootAllocator = new RootAllocator(Integer.MAX_VALUE);

    MockChunkDownloader(List<File> resultFileNames)
    {
      this.resultFileNames = resultFileNames;
      this.currentFileIndex = 0;
    }

    @Override
    public SnowflakeResultChunk getNextChunkToConsume()
    throws SnowflakeSQLException
    {
      if (currentFileIndex < resultFileNames.size())
      {
        ArrowResultChunk resultChunk = new ArrowResultChunk("", 0, 0, 0, rootAllocator);
        try
        {
          InputStream is = new FileInputStream(
              resultFileNames.get(currentFileIndex));
          resultChunk.readArrowStream(is);

          currentFileIndex++;
          return resultChunk;
        }
        catch (IOException e)
        {
          throw new SnowflakeSQLException(ErrorCode.INTERNAL_ERROR, "Failed " +
                                                                    "to read data");
        }
      }
      else
      {
        return null;
      }
    }

    @Override
    public DownloaderMetrics terminate()
    {
      return null;
    }
  }

  private Object[][] generateData(Schema schema, int rowCount)
  {
    Object[][] data = new Object[schema.getFields().size()][rowCount];

    for (int i = 0; i < schema.getFields().size(); i++)
    {
      Types.MinorType type = Types.getMinorTypeForArrowType(
          schema.getFields().get(i).getType());

      switch (type)
      {
        case INT:
        {
          for (int j = 0; j < rowCount; j++)
          {
            data[i][j] = random.nextInt();
          }
          break;
        }

        // add other data types as needed later
      }
    }

    return data;
  }

  private File createArrowFile(String fileName, Schema schema, Object[][] data,
                               int rowsPerRecordBatch)
  throws IOException
  {
    File file = resultFolder.newFile(fileName);
    VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);

    try (ArrowWriter writer = new ArrowStreamWriter(
        root, new DictionaryProvider.MapDictionaryProvider(),
        new FileOutputStream(file)))
    {
      writer.start();

      for (int i = 0; i < data[0].length; )
      {
        int rowsToAppend = Math.min(rowsPerRecordBatch, data[0].length - i);
        root.setRowCount(rowsToAppend);

        for (int j = 0; j < data.length; j++)
        {
          FieldVector vector = root.getFieldVectors().get(j);

          switch (vector.getMinorType())
          {
            case INT:
              writeIntToField(vector, data[j], i, rowsToAppend);
              break;
          }
        }

        writer.writeBatch();
        i += rowsToAppend;
      }
    }

    return file;
  }

  private void writeIntToField(FieldVector fieldVector, Object[] data,
                               int startIndex, int rowsToAppend)
  {
    IntVector intVector = (IntVector) fieldVector;
    intVector.setInitialCapacity(rowsToAppend);
    intVector.allocateNew();
    for (int i = 0; i < rowsToAppend; i++)
    {
      intVector.setSafe(i, 1, (int) data[startIndex + i]);
    }
    // how many are set
    fieldVector.setValueCount(rowsToAppend);
  }
}
