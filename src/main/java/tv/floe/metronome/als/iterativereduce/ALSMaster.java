package tv.floe.metronome.als.iterativereduce;

import org.apache.commons.math.random.RandomGenerator;
import org.apache.mahout.cf.taste.impl.common.FastByIDMap;

import com.google.common.base.Preconditions;

/**
 * <p>Implements the Alternating Least Squares algorithm described in
 * <a href="http://www2.research.att.com/~yifanhu/PUB/cf.pdf">"Collaborative Filtering for Implicit Feedback Datasets"</a>
 * by Yifan Hu, Yehuda Koren, and Chris Volinsky.</p> 
 * 
 * Also based off work by Sean Owen
 * 
 * https://code.google.com/p/myrrix-recommender/source/browse/trunk/online/src/net/myrrix/online/factorizer/als/AlternatingLeastSquares.java#433
 * 
 * 
 */
public class ALSMaster {
	
	  /** Default number of features to use when building the model. */
	  int DEFAULT_FEATURES = 30;
	  
	 /** Default alpha from the ALS algorithm. */
	  public static final double DEFAULT_ALPHA = 1.0;
	  /** Default lambda factor; this is multiplied by alpha. */
	  public static final double DEFAULT_LAMBDA = 0.1;
	  public static final double DEFAULT_CONVERGENCE_THRESHOLD = 0.001;
	  public static final int DEFAULT_MAX_ITERATIONS = 30;

	  private static final int WORK_UNIT_SIZE = 100;
	  private static final int NUM_USER_ITEMS_TO_TEST_CONVERGENCE = 100;
	  
	  private static final long LOG_INTERVAL = 100000;
	  private static final int MAX_FAR_FROM_VECTORS = 100000;
	  
	  // This will cause the ALS algorithm to reconstruction the input matrix R, rather than the
	  // matrix P = R > 0 . Don't use this unless you understand it!
	  private static final boolean RECONSTRUCT_R_MATRIX = 
	      Boolean.parseBoolean(System.getProperty("model.reconstructRMatrix", "false"));
	  // Causes the loss function to exclude entries for any input pairs that do not appear in the
	  // input and are implicitly 0
	  // Likewise, don't touch this for now unless you know what it does.
	  private static final boolean LOSS_IGNORES_UNSPECIFIED = 
	      Boolean.parseBoolean(System.getProperty("model.lossIgnoresUnspecified", "false"));

	  private final FastByIDMap<FastByIDFloatMap> RbyRow;
	  private final FastByIDMap<FastByIDFloatMap> RbyColumn;
	  private final int features;
	  private final double estimateErrorConvergenceThreshold;
	  private final int maxIterations;
	  private FastByIDMap<float[]> X;
	  private FastByIDMap<float[]> Y;
	  private FastByIDMap<float[]> previousY;
	
	
	  
	  
	  
	  /**
	   * Uses default number of feature and convergence threshold.
	   *
	   * @param RbyRow the input R matrix, indexed by row
	   * @param RbyColumn the input R matrix, indexed by column
	   */
	  public ALSMaster(FastByIDMap<FastByIDFloatMap> RbyRow,
	                                 FastByIDMap<FastByIDFloatMap> RbyColumn) {
	    this(RbyRow, RbyColumn, DEFAULT_FEATURES, DEFAULT_CONVERGENCE_THRESHOLD, DEFAULT_MAX_ITERATIONS);
	  }

	  /**
	   * @param RbyRow the input R matrix, indexed by row
	   * @param RbyColumn the input R matrix, indexed by column
	   * @param features number of features, must be positive
	   */
	  public ALSMaster(FastByIDMap<FastByIDFloatMap> RbyRow,
	                                 FastByIDMap<FastByIDFloatMap> RbyColumn,
	                                 int features) {
	    this(RbyRow, RbyColumn, features, DEFAULT_CONVERGENCE_THRESHOLD, DEFAULT_MAX_ITERATIONS);
	  }

	  /**
	   * @param RbyRow the input R matrix, indexed by row
	   * @param RbyColumn the input R matrix, indexed by column
	   * @param features number of features, must be positive
	   * @param estimateErrorConvergenceThreshold when the average absolute difference in estimated user-item
	   *   scores falls below this threshold between iterations, iterations will stop
	   * @param maxIterations caps the number of iterations run. If non-positive, there is no cap.
	   */
	  public ALSMaster(FastByIDMap<FastByIDFloatMap> RbyRow,
	                                 FastByIDMap<FastByIDFloatMap> RbyColumn,
	                                 int features,
	                                 double estimateErrorConvergenceThreshold,
	                                 int maxIterations) {
	    Preconditions.checkNotNull(RbyRow);
	    Preconditions.checkNotNull(RbyColumn);
	    Preconditions.checkArgument(features > 0, "features must be positive: %s", features);
	    Preconditions.checkArgument(estimateErrorConvergenceThreshold > 0.0 && estimateErrorConvergenceThreshold < 1.0,
	                                "threshold must be in (0,1): %s", estimateErrorConvergenceThreshold);
	    this.RbyRow = RbyRow;
	    this.RbyColumn = RbyColumn;
	    this.features = features;
	    this.estimateErrorConvergenceThreshold = estimateErrorConvergenceThreshold;
	    this.maxIterations = maxIterations;
	  }

	  @Override
	  public FastByIDMap<float[]> getX() {
	    return X;
	  }

	  @Override
	  public FastByIDMap<float[]> getY() {
	    return Y;
	  }

	  /**
	   * Does nothing.
	   */
	  @Override
	  public void setPreviousX(FastByIDMap<float[]> previousX) {
	    // do nothing
	  }

	  /**
	   * Sets the initial state of Y used in the computation, typically the Y from a previous
	   * computation. Call before {@link #call()}.
	   */
	  @Override
	  public void setPreviousY(FastByIDMap<float[]> previousY) {
	    this.previousY = previousY;
	  }

	  @Override
	  public Void call() throws ExecutionException, InterruptedException {

	    X = new FastByIDMap<float[]>(RbyRow.size());

	    boolean randomY = previousY == null || previousY.isEmpty();
	    Y = constructInitialY(previousY);

	    // This will be used to compute rows/columns in parallel during iteration

	    String threadsString = System.getProperty("model.threads");
	    int numThreads =
	        threadsString == null ? Runtime.getRuntime().availableProcessors() : Integer.parseInt(threadsString);
	    ExecutorService executor =
	        Executors.newFixedThreadPool(numThreads,
	                                     new ThreadFactoryBuilder().setNameFormat("ALS-%d").setDaemon(true).build());

	    log.info("Iterating using {} threads", numThreads);

	    // Only of any use if using a Y matrix that was specially constructed and fixed ahead of time
	    if (!Boolean.parseBoolean(System.getProperty("model.als.iterate", "true"))) {
	      // Just figure X from Y and stop
	      try {
	        iterateXFromY(executor);
	      } finally {
	        ExecutorUtils.shutdownNowAndAwait(executor);        
	      }
	      return null;      
	    }

	    RandomGenerator random = RandomManager.getRandom();
	    long[] testUserIDs = RandomUtils.chooseAboutNFromStream(NUM_USER_ITEMS_TO_TEST_CONVERGENCE, 
	                                                            RbyRow.keySetIterator(), 
	                                                            RbyRow.size(), 
	                                                            random);
	    long[] testItemIDs = RandomUtils.chooseAboutNFromStream(NUM_USER_ITEMS_TO_TEST_CONVERGENCE, 
	                                                            RbyColumn.keySetIterator(), 
	                                                            RbyColumn.size(), 
	                                                            random);
	    double[][] estimates = new double[testUserIDs.length][testItemIDs.length];
	    if (!X.isEmpty()) {
	      for (int i = 0; i < testUserIDs.length; i++) {
	        for (int j = 0; j < testItemIDs.length; j++) {
	          estimates[i][j] = SimpleVectorMath.dot(X.get(testUserIDs[i]), Y.get(testItemIDs[j])); 
	        }
	      }
	    }
	    // Otherwise X is empty because it's the first ever iteration. Estimates can be left at initial 0 value

	    try {
	      int iterationNumber = 0;
	      while (true) {
	        iterateXFromY(executor);
	        iterateYFromX(executor);
	        DoubleWeightedMean averageAbsoluteEstimateDiff = new DoubleWeightedMean();
	        for (int i = 0; i < testUserIDs.length; i++) {
	          for (int j = 0; j < testItemIDs.length; j++) {
	            double newValue = SimpleVectorMath.dot(X.get(testUserIDs[i]), Y.get(testItemIDs[j]));            
	            double oldValue = estimates[i][j];
	            estimates[i][j] = newValue;
	            averageAbsoluteEstimateDiff.increment(FastMath.abs(newValue - oldValue), FastMath.max(0.0, newValue));
	          }
	        }
	      
	        iterationNumber++;
	        log.info("Finished iteration {}", iterationNumber);
	        if (maxIterations > 0 && iterationNumber >= maxIterations) {
	          log.info("Reached iteration limit");
	          break;
	        }
	        log.info("Avg absolute difference in estimate vs prior iteration: {}", averageAbsoluteEstimateDiff);
	        double convergenceValue = averageAbsoluteEstimateDiff.getResult();
	        if (!LangUtils.isFinite(convergenceValue)) {
	          log.warn("Invalid convergence value, aborting iteration! {}", convergenceValue);
	          break;
	        }
	        // Don't converge after 1 iteration if starting from a random point
	        if (!(randomY && iterationNumber == 1) && convergenceValue < estimateErrorConvergenceThreshold) {
	          log.info("Converged");          
	          break;
	        }
	      }
	    } finally {
	      ExecutorUtils.shutdownNowAndAwait(executor);
	    }
	    return null;
	  }

	  private FastByIDMap<float[]> constructInitialY(FastByIDMap<float[]> previousY) {

	    RandomGenerator random = RandomManager.getRandom();
	    
	    FastByIDMap<float[]> randomY;
	    if (previousY == null || previousY.isEmpty()) {
	      // Common case: have to start from scratch
	      log.info("Starting from new, random Y matrix");      
	      randomY = new FastByIDMap<float[]>(RbyColumn.size());
	      
	    } else {
	      
	      int oldFeatureCount = previousY.entrySet().iterator().next().getValue().length;
	      if (oldFeatureCount > features) {
	        // Fewer features, use some dimensions from prior larger number of features as-is
	        log.info("Feature count has decreased to {}, projecting down previous generation's Y matrix", features);                
	        randomY = new FastByIDMap<float[]>(previousY.size());
	        for (FastByIDMap.MapEntry<float[]> entry : previousY.entrySet()) {
	          float[] oldLargerVector = entry.getValue();
	          float[] newSmallerVector = new float[features];
	          System.arraycopy(oldLargerVector, 0, newSmallerVector, 0, newSmallerVector.length);
	          SimpleVectorMath.normalize(newSmallerVector);
	          randomY.put(entry.getKey(), newSmallerVector);
	        }
	        
	      } else if (oldFeatureCount < features) {
	        log.info("Feature count has increased to {}, using previous generation's Y matrix as subspace", features);        
	        randomY = new FastByIDMap<float[]>(previousY.size());
	        for (FastByIDMap.MapEntry<float[]> entry : previousY.entrySet()) {
	          float[] oldSmallerVector = entry.getValue();
	          float[] newLargerVector = new float[features];
	          System.arraycopy(oldSmallerVector, 0, newLargerVector, 0, oldSmallerVector.length);
	          // Fill in new dimensions with random values
	          for (int i = oldSmallerVector.length; i < newLargerVector.length; i++) {
	            newLargerVector[i] = (float) random.nextGaussian();
	          }
	          SimpleVectorMath.normalize(newLargerVector);          
	          randomY.put(entry.getKey(), newLargerVector);
	        }
	        
	      } else {
	        // Common case: previous generation is same number of features
	        log.info("Starting from previous generation's Y matrix");        
	        randomY = previousY;
	      }
	    }
	    
	    List<float[]> recentVectors = Lists.newArrayList();
	    for (FastByIDMap.MapEntry<float[]> entry : randomY.entrySet()) {
	      if (recentVectors.size() >= MAX_FAR_FROM_VECTORS) {
	        break;
	      }
	      recentVectors.add(entry.getValue());
	    }
	    LongPrimitiveIterator it = RbyColumn.keySetIterator();
	    long count = 0;
	    while (it.hasNext()) {
	      long id = it.nextLong();
	      if (!randomY.containsKey(id)) {
	        float[] vector = RandomUtils.randomUnitVectorFarFrom(features, recentVectors, random);
	        randomY.put(id, vector);
	        if (recentVectors.size() < MAX_FAR_FROM_VECTORS) {
	          recentVectors.add(vector);
	        }
	      }
	      if (++count % LOG_INTERVAL == 0) {
	        log.info("Computed {} initial Y rows", count);
	      }
	    }
	    log.info("Constructed initial Y");
	    return randomY;
	  }

	  /**
	   * Runs one iteration to compute X from Y.
	   */
	  private void iterateXFromY(ExecutorService executor) throws ExecutionException, InterruptedException {

	    RealMatrix YTY = MatrixUtils.transposeTimesSelf(Y);
	    Collection<Future<?>> futures = Lists.newArrayList();
	    addWorkers(RbyRow, Y, YTY, X, executor, futures);

	    int count = 0;
	    long total = 0;
	    for (Future<?> f : futures) {
	      f.get();
	      count += WORK_UNIT_SIZE;
	      if (count >= LOG_INTERVAL) {
	        total += count;
	        JVMEnvironment env = new JVMEnvironment();
	        log.info("{} X/tag rows computed ({}MB heap)", total, env.getUsedMemoryMB());
	        if (env.getPercentUsedMemory() > 95) {
	          log.warn("Memory is low. Increase heap size with -Xmx, decrease new generation size with larger " +
	                   "-XX:NewRatio value, and/or use -XX:+UseCompressedOops");
	        }
	        count = 0;
	      }
	    }
	  }

	  /**
	   * Runs one iteration to compute Y from X.
	   */
	  private void iterateYFromX(ExecutorService executor) throws ExecutionException, InterruptedException {

	    RealMatrix XTX = MatrixUtils.transposeTimesSelf(X);
	    Collection<Future<?>> futures = Lists.newArrayList();
	    addWorkers(RbyColumn, X, XTX, Y, executor, futures);

	    int count = 0;
	    long total = 0;
	    for (Future<?> f : futures) {
	      f.get();
	      count += WORK_UNIT_SIZE;
	      if (count >= LOG_INTERVAL) {
	        total += count;
	        JVMEnvironment env = new JVMEnvironment();
	        log.info("{} Y/tag rows computed ({}MB heap)", total, env.getUsedMemoryMB());
	        if (env.getPercentUsedMemory() > 95) {
	          log.warn("Memory is low. Increase heap size with -Xmx, decrease new generation size with larger " +
	                   "-XX:NewRatio value, and/or use -XX:+UseCompressedOops");
	        }
	        count = 0;
	      }
	    }
	  }

	  private void addWorkers(FastByIDMap<FastByIDFloatMap> R,
	                          FastByIDMap<float[]> M,
	                          RealMatrix MTM, 
	                          FastByIDMap<float[]> MTags,
	                          ExecutorService executor,                          
	                          Collection<Future<?>> futures) {
	    if (R != null) {
	      List<Pair<Long, FastByIDFloatMap>> workUnit = Lists.newArrayListWithCapacity(WORK_UNIT_SIZE);
	      for (FastByIDMap.MapEntry<FastByIDFloatMap> entry : R.entrySet()) {
	        workUnit.add(new Pair<Long,FastByIDFloatMap>(entry.getKey(), entry.getValue()));
	        if (workUnit.size() == WORK_UNIT_SIZE) {
	          futures.add(executor.submit(new Worker(features, M, MTM, MTags, workUnit)));
	          workUnit = Lists.newArrayListWithCapacity(WORK_UNIT_SIZE);
	        }
	      }
	      if (!workUnit.isEmpty()) {
	        futures.add(executor.submit(new Worker(features, M, MTM, MTags, workUnit)));
	      }
	    }
	  }	  
	

}