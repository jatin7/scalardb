package com.scalar.database.storage.cassandra;

import static com.scalar.database.api.ConditionalExpression.Operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.database.api.ConditionalExpression;
import com.scalar.database.api.Delete;
import com.scalar.database.api.DeleteIf;
import com.scalar.database.api.DeleteIfExists;
import com.scalar.database.api.DistributedStorage;
import com.scalar.database.api.Get;
import com.scalar.database.api.Put;
import com.scalar.database.api.PutIf;
import com.scalar.database.api.PutIfExists;
import com.scalar.database.api.PutIfNotExists;
import com.scalar.database.api.Result;
import com.scalar.database.api.Scan;
import com.scalar.database.api.Scanner;
import com.scalar.database.config.DatabaseConfig;
import com.scalar.database.exception.storage.ExecutionException;
import com.scalar.database.exception.storage.InvalidUsageException;
import com.scalar.database.exception.storage.MultiPartitionException;
import com.scalar.database.exception.storage.NoMutationException;
import com.scalar.database.exception.storage.RetriableExecutionException;
import com.scalar.database.io.BooleanValue;
import com.scalar.database.io.IntValue;
import com.scalar.database.io.Key;
import com.scalar.database.io.TextValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.IntStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class CassandraIntegrationTest {
  private static final String KEYSPACE = "integration_testing";
  private static final String TABLE = "test_table";
  private static final String CONTACT_POINT = "localhost";
  private static final String USERNAME = "cassandra";
  private static final String PASSWORD = "cassandra";
  private static final String CREATE_KEYSPACE_STMT =
      "CREATE KEYSPACE "
          + KEYSPACE
          + " WITH REPLICATION = {'class': 'SimpleStrategy', 'replication_factor': 1 }";
  private static final String CREATE_TABLE_STMT =
      "CREATE TABLE "
          + KEYSPACE
          + "."
          + TABLE
          + " (c1 int, c2 text, c3 int, c4 int, c5 boolean, PRIMARY KEY((c1), c4))";
  private static final String DROP_KEYSPACE_STMT = "DROP KEYSPACE " + KEYSPACE;
  private static final String TRUNCATE_TABLE_STMT = "TRUNCATE " + KEYSPACE + "." + TABLE;
  private static final String COL_NAME1 = "c1";
  private static final String COL_NAME2 = "c2";
  private static final String COL_NAME3 = "c3";
  private static final String COL_NAME4 = "c4";
  private static final String COL_NAME5 = "c5";
  private static DistributedStorage storage;
  private List<Put> puts;
  private List<Delete> deletes;

  @Before
  public void setUp() throws Exception {
    storage.with(KEYSPACE, TABLE);
  }

  @After
  public void tearDown() throws Exception {
    ProcessBuilder builder;
    Process process;
    int ret;

    // truncate the TABLE
    builder =
        new ProcessBuilder("cqlsh", "-u", USERNAME, "-p", PASSWORD, "-e", TRUNCATE_TABLE_STMT);
    process = builder.start();
    ret = process.waitFor();
    if (ret != 0) {
      Assert.fail("TRUNCATE TABLE failed.");
    }
  }

  @Test
  public void operation_NoTargetGiven_ShouldThrowIllegalArgumentException() {
    // Arrange
    storage.with(null, TABLE);
    Key partitionKey = new Key(new IntValue(COL_NAME1, 0));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, 0));
    Get get = new Get(partitionKey, clusteringKey);

    // Act Assert
    assertThatThrownBy(
            () -> {
              storage.get(get);
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void get_GetWithPartitionKeyAndClusteringKeyGiven_ShouldRetrieveSingleResult()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Get get = prepareGet(pKey, 0);
    Optional<Result> actual = storage.get(get);

    // Assert
    assertThat(actual.isPresent()).isTrue();
    assertThat(actual.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get().getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
  }

  @Test
  public void get_GetWithoutPartitionKeyGiven_ShouldThrowInvalidUsageException() {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act Assert
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Get get = new Get(partitionKey);
    assertThatThrownBy(
            () -> {
              storage.get(get);
            })
        .isInstanceOf(InvalidUsageException.class);
  }

  @Test
  public void get_GetWithProjectionsGiven_ShouldRetrieveSpecifiedValues()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;
    int cKey = 0;

    // Act
    Get get = prepareGet(pKey, cKey);
    get.withProjection(COL_NAME1).withProjection(COL_NAME2).withProjection(COL_NAME3);
    Optional<Result> actual = storage.get(get);

    // Assert
    assertThat(actual.isPresent()).isTrue();
    assertThat(actual.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get().getValue(COL_NAME2))
        .isEqualTo(Optional.of(new TextValue(COL_NAME2, Integer.toString(pKey + cKey))));
    assertThat(actual.get().getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, pKey + cKey)));
    assertThat(actual.get().getValue(COL_NAME4).isPresent()).isTrue(); // since it's clustering key
    assertThat(actual.get().getValue(COL_NAME5).isPresent()).isFalse();
  }

  @Test
  public void scan_ScanWithPartitionKeyGiven_ShouldRetrieveMultipleResults()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));
    List<Result> actual = new ArrayList<>();
    storage.scan(scan).forEach(r -> actual.add(r)); // use iterator

    // Assert
    assertThat(actual.size()).isEqualTo(3);
    assertThat(actual.get(0).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
    assertThat(actual.get(1).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
    assertThat(actual.get(2).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(2).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 2)));
  }

  @Test
  public void scan_ScanWithPartitionKeyGivenAndResultsIteratedWithOne_ShouldReturnWhatsPut()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));
    Scanner scanner = storage.scan(scan);

    // Assert
    Optional<Result> result = scanner.one();
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(result.get().getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
    result = scanner.one();
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(result.get().getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
    result = scanner.one();
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(result.get().getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, 2)));
    result = scanner.one();
    assertThat(result.isPresent()).isFalse();
  }

  @Test
  public void scan_ScanWithPartitionGivenThreeTimes_ShouldRetrieveResultsProperlyEveryTime()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));
    double t1 = System.currentTimeMillis();
    List<Result> actual = storage.scan(scan).all();
    double t2 = System.currentTimeMillis();
    storage.scan(scan);
    double t3 = System.currentTimeMillis();
    storage.scan(scan);
    double t4 = System.currentTimeMillis();

    // Assert
    assertThat(actual.get(0).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
    System.err.println("first: " + (t2 - t1) + " (ms)");
    System.err.println("second: " + (t3 - t2) + " (ms)");
    System.err.println("third: " + (t4 - t3) + " (ms)");
  }

  @Test
  public void scan_ScanWithStartInclusiveRangeGiven_ShouldRetrieveResultsOfGivenRange()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Scan scan =
        new Scan(new Key(new IntValue(COL_NAME1, pKey)))
            .withStart(new Key(new IntValue(COL_NAME4, 0)), true)
            .withEnd(new Key(new IntValue(COL_NAME4, 2)), false);
    List<Result> actual = storage.scan(scan).all();

    // verify
    assertThat(actual.size()).isEqualTo(2);
    assertThat(actual.get(0).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
    assertThat(actual.get(1).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
  }

  @Test
  public void scan_ScanWithEndInclusiveRangeGiven_ShouldRetrieveResultsOfGivenRange()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Scan scan =
        new Scan(new Key(new IntValue(COL_NAME1, pKey)))
            .withStart(new Key(new IntValue(COL_NAME4, 0)), false)
            .withEnd(new Key(new IntValue(COL_NAME4, 2)), true);
    List<Result> actual = storage.scan(scan).all();

    // verify
    assertThat(actual.size()).isEqualTo(2);
    assertThat(actual.get(0).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
    assertThat(actual.get(1).getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 2)));
  }

  @Test
  public void scan_ScanWithOrderAscGiven_ShouldReturnAscendingOrderedResults()
      throws ExecutionException {
    // Arrange
    puts = preparePuts();
    storage.mutate(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
    Scan scan =
        new Scan(new Key(new IntValue(COL_NAME1, 0)))
            .withOrdering(new Scan.Ordering(COL_NAME4, Scan.Ordering.Order.ASC));

    // Act
    List<Result> actual = storage.scan(scan).all();

    // Assert
    assertThat(actual.size()).isEqualTo(3);
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
    assertThat(actual.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
    assertThat(actual.get(2).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 2)));
  }

  @Test
  public void scan_ScanWithOrderDescGiven_ShouldReturnDescendingOrderedResults()
      throws ExecutionException {
    // Arrange
    puts = preparePuts();
    storage.mutate(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
    Scan scan =
        new Scan(new Key(new IntValue(COL_NAME1, 0)))
            .withOrdering(new Scan.Ordering(COL_NAME4, Scan.Ordering.Order.DESC));

    // Act
    List<Result> actual = storage.scan(scan).all();

    // Assert
    assertThat(actual.size()).isEqualTo(3);
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 2)));
    assertThat(actual.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
    assertThat(actual.get(2).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
  }

  @Test
  public void scan_ScanWithLimitGiven_ShouldReturnGivenNumberOfResults() throws ExecutionException {
    // setup
    puts = preparePuts();
    storage.mutate(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));

    Scan scan =
        new Scan(new Key(new IntValue(COL_NAME1, 0)))
            .withOrdering(new Scan.Ordering(COL_NAME4, Scan.Ordering.Order.DESC))
            .withLimit(1);

    // exercise
    List<Result> actual = storage.scan(scan).all();

    // verify
    assertThat(actual.size()).isEqualTo(1);
    assertThat(actual.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 2)));
  }

  @Test
  public void put_SinglePutGiven_ShouldStoreProperly() throws ExecutionException {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));
    Get get = new Get(partitionKey, clusteringKey);

    // Act
    storage.put(puts.get(pKey * 2 + cKey));

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isTrue();
    assertThat(actual.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get().getValue(COL_NAME2))
        .isEqualTo(Optional.of(new TextValue(COL_NAME2, Integer.toString(pKey + cKey))));
    assertThat(actual.get().getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, pKey + cKey)));
    assertThat(actual.get().getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey)));
    assertThat(actual.get().getValue(COL_NAME5))
        .isEqualTo(Optional.of(new BooleanValue(COL_NAME5, (cKey % 2 == 0) ? true : false)));
  }

  @Test
  public void put_SinglePutWithIfNotExistsGiven_ShouldStoreProperly() throws ExecutionException {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    puts.get(0).withCondition(new PutIfNotExists());
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));
    Get get = new Get(partitionKey, clusteringKey);

    // Act
    storage.put(puts.get(0));
    puts.get(0).withValue(new IntValue(COL_NAME3, Integer.MAX_VALUE));
    assertThatThrownBy(
            () -> {
              storage.put(puts.get(0));
            })
        .isInstanceOf(NoMutationException.class);

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isTrue();
    assertThat(actual.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get().getValue(COL_NAME2))
        .isEqualTo(Optional.of(new TextValue(COL_NAME2, Integer.toString(pKey + cKey))));
    assertThat(actual.get().getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, pKey + cKey)));
    assertThat(actual.get().getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey)));
    assertThat(actual.get().getValue(COL_NAME5))
        .isEqualTo(Optional.of(new BooleanValue(COL_NAME5, (cKey % 2 == 0) ? true : false)));
  }

  @Test
  public void put_MultiplePutGiven_ShouldStoreProperly() throws Exception {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));

    // Act
    assertThatCode(
            () -> {
              storage.put(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(scan).all();
    assertThat(results.size()).isEqualTo(3);
    assertThat(results.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey)));
    assertThat(results.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey + 1)));
    assertThat(results.get(2).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey + 2)));
  }

  @Test
  public void put_MultiplePutWithIfNotExistsGiven_ShouldStoreProperly() throws Exception {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    puts.get(0).withCondition(new PutIfNotExists());
    puts.get(1).withCondition(new PutIfNotExists());
    puts.get(2).withCondition(new PutIfNotExists());
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));

    // Act
    assertThatCode(
            () -> {
              storage.put(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(scan).all();
    assertThat(results.size()).isEqualTo(3);
    assertThat(results.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey)));
    assertThat(results.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey + 1)));
    assertThat(results.get(2).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey + 2)));
  }

  @Test
  public void put_MultiplePutWithIfNotExistsGivenWhenOneExists_ShouldThrowNoMutationException()
      throws Exception {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    assertThatCode(
            () -> {
              storage.put(puts.get(0));
            })
        .doesNotThrowAnyException();
    puts.get(0).withCondition(new PutIfNotExists());
    puts.get(1).withCondition(new PutIfNotExists());
    puts.get(2).withCondition(new PutIfNotExists());
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));

    // Act
    assertThatThrownBy(
            () -> {
              storage.put(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
            })
        .isInstanceOf(NoMutationException.class);

    // Assert
    List<Result> results = storage.scan(scan).all();
    assertThat(results.size()).isEqualTo(1);
    assertThat(results.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey)));
  }

  @Test
  public void
      put_MultiplePutWithDifferentPartitionsWithIfNotExistsGiven_ShouldThrowMultiPartitionException()
          throws ExecutionException {
    // Arrange
    puts = preparePuts();
    puts.get(0).withCondition(new PutIfNotExists());
    puts.get(3).withCondition(new PutIfNotExists());
    puts.get(6).withCondition(new PutIfNotExists());

    // Act
    assertThatThrownBy(
            () -> {
              storage.put(Arrays.asList(puts.get(0), puts.get(3), puts.get(6)));
            })
        .isInstanceOf(RetriableExecutionException.class)
        .hasCauseExactlyInstanceOf(MultiPartitionException.class);

    // Assert
    List<Result> results;
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 0)))).all();
    assertThat(results.size()).isEqualTo(0);
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 3)))).all();
    assertThat(results.size()).isEqualTo(0);
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 6)))).all();
    assertThat(results.size()).isEqualTo(0);
  }

  @Test
  public void put_MultiplePutWithDifferentPartitionsGiven_ShouldThrowMultiPartitionException()
      throws ExecutionException {
    // Arrange
    puts = preparePuts();

    // Act
    assertThatThrownBy(
            () -> {
              storage.put(Arrays.asList(puts.get(0), puts.get(3), puts.get(6)));
            })
        .isInstanceOf(RetriableExecutionException.class)
        .hasCauseExactlyInstanceOf(MultiPartitionException.class);

    // Assert
    List<Result> results;
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 0)))).all();
    assertThat(results.size()).isEqualTo(0);
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 3)))).all();
    assertThat(results.size()).isEqualTo(0);
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 6)))).all();
    assertThat(results.size()).isEqualTo(0);
  }

  @Test
  public void put_MultiplePutWithDifferentConditionsGiven_ShouldStoreProperly()
      throws ExecutionException {
    // Arrange
    puts = preparePuts();
    storage.put(puts.get(1));
    puts.get(0).withCondition(new PutIfNotExists());
    puts.get(1)
        .withCondition(
            new PutIf(new ConditionalExpression(COL_NAME2, new TextValue("1"), Operator.EQ)));

    // Act
    assertThatCode(
            () -> {
              storage.put(Arrays.asList(puts.get(0), puts.get(1)));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 0)))).all();
    assertThat(results.size()).isEqualTo(2);
    assertThat(results.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 0)));
    assertThat(results.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, 1)));
  }

  @Test
  public void put_PutWithIfExistsGivenWhenNoSuchRecord_ShouldThrowNoMutationException()
      throws ExecutionException {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    puts.get(0).withCondition(new PutIfExists());
    Get get = prepareGet(pKey, cKey);

    // Act Assert
    assertThatThrownBy(
            () -> {
              storage.put(puts.get(0));
            })
        .isInstanceOf(NoMutationException.class);

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isFalse();
  }

  @Test
  public void put_PutWithIfExistsGivenWhenSuchRecordExists_ShouldUpdateRecord()
      throws ExecutionException {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Get get = prepareGet(pKey, cKey);

    // Act Assert
    storage.put(puts.get(0));
    puts.get(0).withCondition(new PutIfExists());
    puts.get(0).withValue(new IntValue(COL_NAME3, Integer.MAX_VALUE));
    assertThatCode(
            () -> {
              storage.put(puts.get(0));
            })
        .doesNotThrowAnyException();

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isTrue();
    Result result = actual.get();
    assertThat(result.getValue(COL_NAME1)).isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(result.getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey)));
    assertThat(result.getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, Integer.MAX_VALUE)));
  }

  @Test
  public void put_PutWithIfGivenWhenSuchRecordExists_ShouldUpdateRecord()
      throws ExecutionException {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Get get = prepareGet(pKey, cKey);

    // Act Assert
    storage.put(puts.get(0));
    puts.get(0)
        .withCondition(
            new PutIf(
                new ConditionalExpression(COL_NAME3, new IntValue(pKey + cKey), Operator.EQ)));
    puts.get(0).withValue(new IntValue(COL_NAME3, Integer.MAX_VALUE));
    assertThatCode(
            () -> {
              storage.put(puts.get(0));
            })
        .doesNotThrowAnyException();

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isTrue();
    Result result = actual.get();
    assertThat(result.getValue(COL_NAME1)).isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(result.getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey)));
    assertThat(result.getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, Integer.MAX_VALUE)));
  }

  @Test
  public void put_PutWithIfGivenWhenNoSuchRecord_ShouldThrowNoMutationException()
      throws ExecutionException {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Get get = prepareGet(pKey, cKey);

    // Act Assert
    storage.put(puts.get(0));
    puts.get(0)
        .withCondition(
            new PutIf(
                new ConditionalExpression(COL_NAME3, new IntValue(pKey + cKey + 1), Operator.EQ)));
    puts.get(0).withValue(new IntValue(COL_NAME3, Integer.MAX_VALUE));
    assertThatThrownBy(
            () -> {
              storage.put(puts.get(0));
            })
        .isInstanceOf(NoMutationException.class);

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isTrue();
    Result result = actual.get();
    assertThat(result.getValue(COL_NAME1)).isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(result.getValue(COL_NAME4)).isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey)));
    assertThat(result.getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, pKey + cKey)));
  }

  @Test
  public void delete_DeleteWithPartitionKeyGiven_ShouldDeleteRecordsProperly()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Delete delete = new Delete(partitionKey);
    assertThatCode(
            () -> {
              storage.delete(delete);
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> actual = storage.scan(new Scan(partitionKey)).all();
    assertThat(actual.size()).isEqualTo(0);
  }

  @Test
  public void delete_DeleteWithPartitionKeyAndClusteringKeyGiven_ShouldDeleteSingleRecordProperly()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;
    int cKey = 0;
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));

    // Act
    Delete delete = prepareDelete(pKey, cKey);
    assertThatCode(
            () -> {
              storage.delete(delete);
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(new Scan(partitionKey)).all();
    assertThat(results.size()).isEqualTo(2);
    assertThat(results.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey + 1)));
    assertThat(results.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey + 2)));
  }

  @Test
  public void delete_DeleteWithIfExistsGivenWhenNoSuchRecord_ShouldThrowNoMutationException()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act Assert
    Delete delete = prepareDelete(pKey, Integer.MAX_VALUE);
    delete.withCondition(new DeleteIfExists());
    assertThatThrownBy(
            () -> {
              storage.delete(delete);
            })
        .isInstanceOf(NoMutationException.class);
  }

  @Test
  public void delete_DeleteWithIfExistsGivenWhenSuchRecordExists_ShouldDeleteProperly()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;
    int cKey = 0;
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));

    // Act
    Delete delete = prepareDelete(pKey, cKey);
    delete.withCondition(new DeleteIfExists());
    assertThatCode(
            () -> {
              storage.delete(delete);
            })
        .doesNotThrowAnyException();

    // Assert
    Optional<Result> actual = storage.get(new Get(partitionKey, clusteringKey));
    assertThat(actual.isPresent()).isFalse();
  }

  @Test
  public void delete_DeleteWithIfGivenWhenNoSuchRecord_ShouldThrowNoMutationException()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;
    int cKey = 0;
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));

    // Act
    Delete delete = prepareDelete(pKey, cKey);
    delete.withCondition(
        new DeleteIf(
            new ConditionalExpression(
                COL_NAME2, new TextValue(Integer.toString(Integer.MAX_VALUE)), Operator.EQ)));
    assertThatThrownBy(
            () -> {
              storage.delete(delete);
            })
        .isInstanceOf(NoMutationException.class);

    // Assert
    Optional<Result> actual = storage.get(new Get(partitionKey, clusteringKey));
    assertThat(actual.isPresent()).isTrue();
  }

  @Test
  public void delete_DeleteWithIfGivenWhenSuchRecordExists_ShouldDeleteProperly()
      throws ExecutionException {
    // Arrange
    populateRecords();
    int pKey = 0;
    int cKey = 0;
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));

    // Act
    Delete delete = prepareDelete(pKey, cKey);
    delete.withCondition(
        new DeleteIf(
            new ConditionalExpression(
                COL_NAME2, new TextValue(Integer.toString(pKey)), Operator.EQ)));
    assertThatCode(
            () -> {
              storage.delete(delete);
            })
        .doesNotThrowAnyException();

    // Assert
    Optional<Result> actual = storage.get(new Get(partitionKey, clusteringKey));
    assertThat(actual.isPresent()).isFalse();
  }

  @Test
  public void delete_MultipleDeleteWithDifferentConditionsGiven_ShouldDeleteProperly()
      throws ExecutionException {
    // Arrange
    puts = preparePuts();
    deletes = prepareDeletes();
    storage.mutate(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
    deletes.get(0).withCondition(new DeleteIfExists());
    deletes
        .get(1)
        .withCondition(
            new DeleteIf(new ConditionalExpression(COL_NAME2, new TextValue("1"), Operator.EQ)));

    // Act
    assertThatCode(
            () -> {
              storage.delete(Arrays.asList(deletes.get(0), deletes.get(1), deletes.get(2)));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 0)))).all();
    assertThat(results.size()).isEqualTo(0);
  }

  @Test
  public void mutate_MultiplePutGiven_ShouldStoreProperly() throws Exception {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));

    // Act
    assertThatCode(
            () -> {
              storage.mutate(Arrays.asList(puts.get(0), puts.get(1), puts.get(2)));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(scan).all();
    assertThat(results.size()).isEqualTo(3);
    assertThat(results.get(0).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey)));
    assertThat(results.get(1).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey + 1)));
    assertThat(results.get(2).getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, pKey + cKey + 2)));
  }

  @Test
  public void mutate_MultiplePutWithDifferentPartitionsGiven_ShouldThrowMultiPartitionException()
      throws Exception {
    // Arrange
    puts = preparePuts();

    // Act
    assertThatCode(
            () -> {
              storage.mutate(Arrays.asList(puts.get(0), puts.get(3), puts.get(6)));
            })
        .isInstanceOf(RetriableExecutionException.class)
        .hasCauseExactlyInstanceOf(MultiPartitionException.class);

    // Assert
    List<Result> results;
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 0)))).all();
    assertThat(results.size()).isEqualTo(0);
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 3)))).all();
    assertThat(results.size()).isEqualTo(0);
    results = storage.scan(new Scan(new Key(new IntValue(COL_NAME1, 6)))).all();
    assertThat(results.size()).isEqualTo(0);
  }

  @Test
  public void mutate_PutAndDeleteGiven_ShouldUpdateAndDeleteRecordsProperly() throws Exception {
    // Arrange
    populateRecords();
    puts = preparePuts();
    puts.get(1).withValue(new IntValue(COL_NAME3, Integer.MAX_VALUE));
    puts.get(2).withValue(new IntValue(COL_NAME3, Integer.MIN_VALUE));

    int pKey = 0;
    int cKey = 0;
    Delete delete = prepareDelete(pKey, cKey);

    Scan scan = new Scan(new Key(new IntValue(COL_NAME1, pKey)));

    // Act
    assertThatCode(
            () -> {
              storage.mutate(Arrays.asList(delete, puts.get(1), puts.get(2)));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> results = storage.scan(scan).all();
    assertThat(results.size()).isEqualTo(2);
    assertThat(results.get(0).getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, Integer.MAX_VALUE)));
    assertThat(results.get(1).getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, Integer.MIN_VALUE)));
  }

  @Test
  public void mutate_SinglePutGiven_ShouldStoreProperly() throws Exception {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    puts = preparePuts();
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));
    Get get = new Get(partitionKey, clusteringKey);

    // Act
    storage.mutate(Arrays.asList(puts.get(pKey * 2 + cKey)));

    // Assert
    Optional<Result> actual = storage.get(get);
    assertThat(actual.isPresent()).isTrue();
    assertThat(actual.get().getValue(COL_NAME1))
        .isEqualTo(Optional.of(new IntValue(COL_NAME1, pKey)));
    assertThat(actual.get().getValue(COL_NAME2))
        .isEqualTo(Optional.of(new TextValue(COL_NAME2, Integer.toString(pKey + cKey))));
    assertThat(actual.get().getValue(COL_NAME3))
        .isEqualTo(Optional.of(new IntValue(COL_NAME3, pKey + cKey)));
    assertThat(actual.get().getValue(COL_NAME4))
        .isEqualTo(Optional.of(new IntValue(COL_NAME4, cKey)));
    assertThat(actual.get().getValue(COL_NAME5))
        .isEqualTo(Optional.of(new BooleanValue(COL_NAME5, (cKey % 2 == 0) ? true : false)));
  }

  @Test
  public void mutate_SingleDeleteGiven_ShouldDeleteRecordProperly() throws Exception {
    // Arrange
    populateRecords();
    int pKey = 0;

    // Act
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Delete delete = new Delete(partitionKey);
    assertThatCode(
            () -> {
              storage.mutate(Arrays.asList(delete));
            })
        .doesNotThrowAnyException();

    // Assert
    List<Result> actual = storage.scan(new Scan(partitionKey)).all();
    assertThat(actual.size()).isEqualTo(0);
  }

  @Test
  public void mutate_EmptyListGiven_ShouldThrowIllegalArgumentException() throws Exception {
    // Act
    assertThatCode(
            () -> {
              storage.mutate(new ArrayList<>());
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void put_PutWithoutClusteringKeyGiven_ShouldThrowIllegalArgumentException() {
    // Arrange
    int pKey = 0;
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Put put = new Put(partitionKey);

    // Act Assert
    assertThatCode(
            () -> {
              storage.put(put);
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void put_IncorrectPutGiven_ShouldThrowIllegalArgumentException() {
    // Arrange
    int pKey = 0;
    int cKey = 0;
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Put put = new Put(partitionKey).withValue(new IntValue(COL_NAME4, cKey));

    // Act Assert
    assertThatCode(
            () -> {
              storage.put(put);
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void populateRecords() {
    puts = preparePuts();
    puts.forEach(
        p -> {
          assertThatCode(
                  () -> {
                    storage.put(p);
                  })
              .doesNotThrowAnyException();
        });
  }

  private Get prepareGet(int pKey, int cKey) {
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));
    return new Get(partitionKey, clusteringKey);
  }

  private List<Put> preparePuts() {
    List<Put> puts = new ArrayList<>();

    IntStream.range(0, 5)
        .forEach(
            i -> {
              IntStream.range(0, 3)
                  .forEach(
                      j -> {
                        Key partitionKey = new Key(new IntValue(COL_NAME1, i));
                        Key clusteringKey = new Key(new IntValue(COL_NAME4, j));
                        Put put =
                            new Put(partitionKey, clusteringKey)
                                .withValue(new TextValue(COL_NAME2, Integer.toString(i + j)))
                                .withValue(new IntValue(COL_NAME3, i + j))
                                .withValue(
                                    new BooleanValue(COL_NAME5, (j % 2 == 0) ? true : false));
                        puts.add(put);
                      });
            });

    return puts;
  }

  private Delete prepareDelete(int pKey, int cKey) {
    Key partitionKey = new Key(new IntValue(COL_NAME1, pKey));
    Key clusteringKey = new Key(new IntValue(COL_NAME4, cKey));
    return new Delete(partitionKey, clusteringKey);
  }

  private List<Delete> prepareDeletes() {
    List<Delete> deletes = new ArrayList<>();

    IntStream.range(0, 5)
        .forEach(
            i -> {
              IntStream.range(0, 3)
                  .forEach(
                      j -> {
                        Key partitionKey = new Key(new IntValue(COL_NAME1, i));
                        Key clusteringKey = new Key(new IntValue(COL_NAME4, j));
                        Delete delete = new Delete(partitionKey, clusteringKey);
                        deletes.add(delete);
                      });
            });

    return deletes;
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    ProcessBuilder builder;
    Process process;
    int ret;

    builder =
        new ProcessBuilder("cqlsh", "-u", USERNAME, "-p", PASSWORD, "-e", CREATE_KEYSPACE_STMT);
    process = builder.start();
    ret = process.waitFor();
    if (ret != 0) {
      Assert.fail("CREATE KEYSPACE failed.");
    }

    builder = new ProcessBuilder("cqlsh", "-u", USERNAME, "-p", PASSWORD, "-e", CREATE_TABLE_STMT);
    process = builder.start();
    ret = process.waitFor();
    if (ret != 0) {
      Assert.fail("CREATE TABLE failed.");
    }

    // reuse this storage instance through the tests
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.CONTACT_POINTS, CONTACT_POINT);
    props.setProperty(DatabaseConfig.USERNAME, USERNAME);
    props.setProperty(DatabaseConfig.PASSWORD, PASSWORD);
    storage = new Cassandra(new DatabaseConfig(props));
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    ProcessBuilder builder;
    Process process;
    int ret;

    builder = new ProcessBuilder("cqlsh", "-u", USERNAME, "-p", PASSWORD, "-e", DROP_KEYSPACE_STMT);
    process = builder.start();
    ret = process.waitFor();
    if (ret != 0) {
      Assert.fail("DROP KEYSPACE failed.");
    }
  }
}
