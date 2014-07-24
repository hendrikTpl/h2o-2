package hex.drf;

import static hex.drf.TreeMeasuresCollector.asSSE;
import static hex.drf.TreeMeasuresCollector.asVotes;
import static water.util.Utils.div;
import static water.util.Utils.sum;
import hex.ConfusionMatrix;
import hex.VarImp;
import hex.drf.TreeMeasuresCollector.TreeMeasures;
import hex.drf.TreeMeasuresCollector.TreeSSE;
import hex.drf.TreeMeasuresCollector.TreeVotes;
import hex.gbm.*;
import hex.gbm.DTree.DecidedNode;
import hex.gbm.DTree.LeafNode;
import hex.gbm.DTree.TreeModel.CompressedTree;
import hex.gbm.DTree.TreeModel.TreeStats;
import hex.gbm.DTree.UndecidedNode;

import java.util.Arrays;
import java.util.Random;

import water.*;
import water.H2O.H2OCountedCompleter;
import water.api.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.util.*;
import water.util.Log.Tag.Sys;

// Random Forest Trees
public class DRF extends SharedTreeModelBuilder<DRF.DRFModel> {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  static final boolean DEBUG_DETERMINISTIC = false; // enable this for deterministic version of DRF. It will use same seed for each execution. I would prefere here to read this property from system properties.

  @API(help = "Columns to randomly select at each level, or -1 for sqrt(#cols)", filter = Default.class, lmin=-1, lmax=100000)
  int mtries = -1;

  @API(help = "Sample rate, from 0. to 1.0", filter = Default.class, dmin=0, dmax=1, importance=ParamImportance.SECONDARY)
  float sample_rate = 0.6666667f;

  @API(help = "Seed for the random number generator (autogenerated)", filter = Default.class)
  long seed = -1; // To follow R-semantics, each call of RF should provide different seed. -1 means seed autogeneration

  @API(help = "Check non-contiguous group splits for categorical predictors", filter = Default.class, hide = true)
  boolean do_grpsplit = true;

  @API(help="Run on one node only; no network overhead but fewer cpus used.  Suitable for small datasets.", filter=myClassFilter.class, importance=ParamImportance.SECONDARY)
  public boolean build_tree_one_node = false;
  class myClassFilter extends DRFCopyDataBoolean { myClassFilter() { super("source"); } }

  @API(help = "Computed number of split features", importance=ParamImportance.EXPERT)
  protected int _mtry; // FIXME remove and replace by mtries

  @API(help = "Autogenerated seed", importance=ParamImportance.EXPERT)
  protected long _seed; // FIXME remove and replace by seed

  // Fixed seed generator for DRF
  private static final Random _seedGenerator = Utils.getDeterRNG(0xd280524ad7fe0602L);

  // --- Private data handled only on master node
  // Classification or Regression:
  // Tree votes/SSE of individual trees on OOB rows
  private transient TreeMeasures _treeMeasuresOnOOB;
  // Tree votes/SSE per individual features on permutated OOB rows
  private transient TreeMeasures[/*features*/] _treeMeasuresOnSOOB;

  /** DRF model holding serialized tree and implementing logic for scoring a row */
  public static class DRFModel extends DTree.TreeModel {
    static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
    static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

    @API(help = "Model parameters", json = true)
    private final DRF parameters;    // This is used purely for printing values out.
    @Override public final DRF get_params() { return parameters; }
    @Override public final Request2 job() { return get_params(); }

    @API(help = "Number of columns picked at each split") final int mtries;
    @API(help = "Sample rate") final float sample_rate;
    @API(help = "Seed") final long seed;

    // Params that do not affect model quality:
    //
    public DRFModel(DRF params, Key key, Key dataKey, Key testKey, String names[], String domains[][], String[] cmDomain, int ntrees, int max_depth, int min_rows, int nbins, int mtries, float sample_rate, long seed, int num_folds, float[] priorClassDist, float[] classDist) {
      super(key,dataKey,testKey,names,domains,cmDomain,ntrees, max_depth, min_rows, nbins, num_folds, priorClassDist, classDist);
      this.parameters = Job.hygiene((DRF) params.clone());
      this.mtries = mtries;
      this.sample_rate = sample_rate;
      this.seed = seed;
    }
    private DRFModel(DRFModel prior, DTree[] trees, TreeStats tstats) {
      super(prior, trees, tstats);
      this.parameters = prior.parameters;
      this.mtries = prior.mtries;
      this.sample_rate = prior.sample_rate;
      this.seed = prior.seed;
    }
    private DRFModel(DRFModel prior, double err, ConfusionMatrix cm, VarImp varimp, AUCData validAUC) {
      super(prior, err, cm, varimp, validAUC);
      this.parameters = prior.parameters;
      this.mtries = prior.mtries;
      this.sample_rate = prior.sample_rate;
      this.seed = prior.seed;
    }
    private DRFModel(DRFModel prior, Key[][] treeKeys, double[] errs, ConfusionMatrix[] cms, TreeStats tstats, VarImp varimp, AUCData validAUC) {
      super(prior, treeKeys, errs, cms, tstats, varimp, validAUC);
      this.parameters = prior.parameters;
      this.mtries = prior.mtries;
      this.sample_rate = prior.sample_rate;
      this.seed = prior.seed;
    }

    @Override protected TreeModelType getTreeModelType() { return TreeModelType.DRF; }

    @Override protected float[] score0(double data[], float preds[]) {
      float[] p = super.score0(data, preds);
      int ntrees = ntrees();
      if (p.length==1) { if (ntrees>0) div(p, ntrees); } // regression - compute avg over all trees
      else { // classification
        float s = sum(p);
        if (s>0) div(p, s); // unify over all classes
        p[0] = ModelUtils.getPrediction(p, data);
      }
      return p;
    }
    @Override protected void generateModelDescription(StringBuilder sb) {
      DocGen.HTML.paragraph(sb,"mtries: "+mtries+", Sample rate: "+sample_rate+", Seed: "+seed);
      if (testKey==null && sample_rate==1f) {
        sb.append("<div class=\"alert alert-danger\">There are no out-of-bag data to compute out-of-bag error estimate, since sampling rate is 1!</div>");
      }
    }
    @Override protected void toJavaUnifyPreds(SB bodySb) {
      if (isClassifier()) {
        bodySb.i().p("float sum = 0;").nl();
        bodySb.i().p("for(int i=1; i<preds.length; i++) sum += preds[i];").nl();
        bodySb.i().p("if (sum>0) for(int i=1; i<preds.length; i++) preds[i] /= sum;").nl();
      } else bodySb.i().p("preds[1] = preds[1]/NTREES;").nl();
    }
    @Override protected void setCrossValidationError(ValidatedJob job, double cv_error, water.api.ConfusionMatrix cm, AUCData auc, HitRatio hr) {
      DRFModel drfm = ((DRF)job).makeModel(this, cv_error, cm.cm == null ? null : new ConfusionMatrix(cm.cm, cms[0].nclasses()), this.varimp, auc);
      drfm._have_cv_results = true;
      DKV.put(this._key, drfm); //overwrite this model
    }
  }
  public Frame score( Frame fr ) { return ((DRFModel)UKV.get(dest())).score(fr);  }

  @Override protected Log.Tag.Sys logTag() { return Sys.DRF__; }
  @Override protected DRFModel makeModel(Key outputKey, Key dataKey, Key testKey, int ntrees, String[] names, String[][] domains, String[] cmDomain, float[] priorClassDist, float[] classDist) {
    return new DRFModel(this,outputKey,dataKey,validation==null?null:testKey,names,domains,cmDomain,ntrees, max_depth, min_rows, nbins, mtries, sample_rate, _seed, n_folds, priorClassDist, classDist);
  }

  @Override protected DRFModel makeModel( DRFModel model, double err, ConfusionMatrix cm, VarImp varimp, AUCData validAUC) {
    return new DRFModel(model, err, cm, varimp, validAUC);
  }
  @Override protected DRFModel makeModel( DRFModel model, DTree ktrees[], TreeStats tstats) {
    return new DRFModel(model, ktrees, tstats);
  }
  @Override protected DRFModel updateModel(DRFModel model, DRFModel checkpoint, boolean overwriteCheckpoint) {
    // Do not forget to clone trees in case that we are not going to overwrite checkpoint
    Key[][] treeKeys = null;
    if (!overwriteCheckpoint) throw H2O.unimpl("Cloning of model trees is not implemented yet!");
    else treeKeys = checkpoint.treeKeys;
    return new DRFModel(model, treeKeys, checkpoint.errs, checkpoint.cms, checkpoint.treeStats, checkpoint.varimp, checkpoint.validAUC);
  }
  public DRF() { description = "Distributed RF"; ntrees = 50; max_depth = 20; min_rows = 1; }

  /** Return the query link to this page */
  public static String link(Key k, String content) {
    RString rs = new RString("<a href='/2/DRF.query?source=%$key'>%content</a>");
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  // ==========================================================================

  /** Compute a DRF tree.
   *
   * Start by splitting all the data according to some criteria (minimize
   * variance at the leaves).  Record on each row which split it goes to, and
   * assign a split number to it (for next pass).  On *this* pass, use the
   * split-number to build a per-split histogram, with a per-histogram-bucket
   * variance. */
  @Override protected void execImpl() {
    try {
      logStart();
      buildModel(seed);
      if (n_folds > 0) CrossValUtils.crossValidate(this);
    } finally {
      remove();                   // Remove Job
      state = UKV.<Job>get(self()).state;
      new TAtomic<DRFModel>() {
        @Override
        public DRFModel atomic(DRFModel m) {
          if (m != null) m.get_params().state = state;
          return m;
        }
      }.invoke(dest());
    }
  }

  @Override protected Response redirect() {
    return DRFProgressPage.redirect(this, self(), dest());
  }

  @SuppressWarnings("unused")
  @Override protected void init() {
    super.init();
    // Initialize local variables
    _mtry = (mtries==-1) ? // classification: mtry=sqrt(_ncols), regression: mtry=_ncols/3
        ( classification ? Math.max((int)Math.sqrt(_ncols),1) : Math.max(_ncols/3,1))  : mtries;
    if (!(1 <= _mtry && _mtry <= _ncols)) throw new IllegalArgumentException("Computed mtry should be in interval <1,#cols> but it is " + _mtry);
    if (!(0.0 < sample_rate && sample_rate <= 1.0)) throw new IllegalArgumentException("Sample rate should be interval (0,1> but it is " + sample_rate);
    if (DEBUG_DETERMINISTIC && seed == -1) _seed = 0x1321e74a0192470cL; // fixed version of seed
    else if (seed == -1) _seed = _seedGenerator.nextLong(); else _seed = seed;
    if (sample_rate==1f && validation!=null)
      Log.warn(Sys.DRF__, "Sample rate is 100% and no validation dataset is specified. There are no OOB data to compute out-of-bag error estimation!");
  }

  @Override protected void initAlgo(DRFModel initialModel) {
    // Initialize TreeVotes for classification, MSE arrays for regression
    if (importance) initTreeMeasurements();
  }
  @Override protected void initWorkFrame(DRFModel initialModel, Frame fr) {
    // Append number of trees participating in on-the-fly scoring
    fr.add("OUT_BAG_TREES", response.makeZero());
    // Prepare working columns
    new SetWrkTask().doAll(fr);
    // If there was a check point recompute tree_<_> and oob columns based on predictions from previous trees
    // but only if OOB validation is requested.
    if (validation==null && checkpoint!=null) {
      Timer t = new Timer();
      // Compute oob votes for each output level
      new OOBScorer(_ncols, _nclass, sample_rate, initialModel.treeKeys).doAll(fr);
      Log.info(logTag(), "Reconstructing oob stats from checkpointed model took " + t);
    }
  }

  @Override protected DRFModel buildModel( DRFModel model, final Frame fr, String names[], String domains[][], final Timer t_build ) {
    // The RNG used to pick split columns
    Random rand = createRNG(_seed);
    // To be deterministic get random numbers for previous trees and
    // put random generator to the same state
    for (int i=0; i<_ntreesFromCheckpoint; i++) rand.nextLong();

    int tid;
    DTree[] ktrees = null;
    // Prepare tree statistics
    TreeStats tstats = model.treeStats!=null ? model.treeStats : new TreeStats();
    // Build trees until we hit the limit
    for( tid=0; tid<ntrees; tid++) { // Building tid-tree
      if (tid!=0 || checkpoint==null) { // do not make initial scoring if model already exist
        model = doScoring(model, fr, ktrees, tid, tstats, tid==0, !hasValidation(), build_tree_one_node);
      }
      // At each iteration build K trees (K = nclass = response column domain size)

      // TODO: parallelize more? build more than k trees at each time, we need to care about temporary data
      // Idea: launch more DRF at once.
      Timer kb_timer = new Timer();
      ktrees = buildNextKTrees(fr,_mtry,sample_rate,rand,tid);
      Log.info(logTag(), (tid+1) + ". tree was built " + kb_timer.toString());
      if( !Job.isRunning(self()) ) break; // If canceled during building, do not bulkscore

      // Check latest predictions
      tstats.updateBy(ktrees);
    }

    model = doScoring(model, fr, ktrees, tid, tstats, true, !hasValidation(), build_tree_one_node);
    // Make sure that we did not miss any votes
    assert !importance || _treeMeasuresOnOOB.npredictors() == _treeMeasuresOnSOOB[0/*variable*/].npredictors() : "Missing some tree votes in variable importance voting?!";

    return model;
  }

  private void initTreeMeasurements() {
    assert importance : "Tree votes should be initialized only if variable importance is requested!";
    // Preallocate tree votes
    if (classification) {
      _treeMeasuresOnOOB  = new TreeVotes(ntrees);
      _treeMeasuresOnSOOB = new TreeVotes[_ncols];
      for (int i=0; i<_ncols; i++) _treeMeasuresOnSOOB[i] = new TreeVotes(ntrees);
    } else {
      _treeMeasuresOnOOB  = new TreeSSE(ntrees);
      _treeMeasuresOnSOOB = new TreeSSE[_ncols];
      for (int i=0; i<_ncols; i++) _treeMeasuresOnSOOB[i] = new TreeSSE(ntrees);
    }
  }

  /** On-the-fly version for varimp. After generation a new tree, its tree votes are collected on shuffled
   * OOB rows and variable importance is recomputed.
   * <p>
   * The <a href="http://www.stat.berkeley.edu/~breiman/RandomForests/cc_home.htm#varimp">page</a> says:
   * <cite>
   * "In every tree grown in the forest, put down the oob cases and count the number of votes cast for the correct class.
   * Now randomly permute the values of variable m in the oob cases and put these cases down the tree.
   * Subtract the number of votes for the correct class in the variable-m-permuted oob data from the number of votes
   * for the correct class in the untouched oob data.
   * The average of this number over all trees in the forest is the raw importance score for variable m."
   * </cite>
   * </p>
   * */
  @Override
  protected VarImp doVarImpCalc(final DRFModel model, DTree[] ktrees, final int tid, final Frame fTrain, boolean scale) {
    // Check if we have already serialized 'ktrees'-trees in the model
    assert model.ntrees()-1-_ntreesFromCheckpoint == tid : "Cannot compute DRF varimp since 'ktrees' are not serialized in the model! tid="+tid;
    assert _treeMeasuresOnOOB.npredictors()-1 == tid : "Tree votes over OOB rows for this tree (var ktrees) were not found!";
    // Compute tree votes over shuffled data
    final CompressedTree[/*nclass*/] theTree = model.ctree(tid); // get the last tree FIXME we should pass only keys
    final int nclasses = model.nclasses();
    Futures fs = new Futures();
    for (int var=0; var<_ncols; var++) {
      final int variable = var;
      H2OCountedCompleter task4var = classification ? new H2OCountedCompleter() {
        @Override public void compute2() {
          // Compute this tree votes over all data over given variable
          TreeVotes cd = TreeMeasuresCollector.collectVotes(theTree, nclasses, fTrain, _ncols, sample_rate, variable);
          assert cd.npredictors() == 1;
          asVotes(_treeMeasuresOnSOOB[variable]).append(cd);
          tryComplete();
        }
      } : /* regression */ new H2OCountedCompleter() {
        @Override public void compute2() {
          // Compute this tree votes over all data over given variable
          TreeSSE cd = TreeMeasuresCollector.collectSSE(theTree, nclasses, fTrain, _ncols, sample_rate, variable);
          assert cd.npredictors() == 1;
          asSSE(_treeMeasuresOnSOOB[variable]).append(cd);
          tryComplete();
        }
      };
      H2O.submitTask(task4var); // Fork computation
      fs.add(task4var);
    }
    fs.blockForPending(); // Wait for results
    // Compute varimp for individual features (_ncols)
    final float[] varimp   = new float[_ncols]; // output variable importance
    final float[] varimpSD = new float[_ncols]; // output variable importance sd
    for (int var=0; var<_ncols; var++) {
      double[/*2*/] imp = classification ? asVotes(_treeMeasuresOnSOOB[var]).imp(asVotes(_treeMeasuresOnOOB)) :  asSSE(_treeMeasuresOnSOOB[var]).imp(asSSE(_treeMeasuresOnOOB));
      varimp  [var] = (float) imp[0];
      varimpSD[var] = (float) imp[1];
    }
    return new VarImp.VarImpMDA(varimp, varimpSD, model.ntrees());
  }

  @Override public boolean supportsBagging() { return true; }

  /** Fill work columns:
   *   - classification: set 1 in the corresponding wrk col according to row response
   *   - regression:     copy response into work column (there is only 1 work column) */

  private class SetWrkTask extends MRTask2<SetWrkTask> {
    @Override public void map( Chunk chks[] ) {
      Chunk cy = chk_resp(chks);
      for( int i=0; i<cy._len; i++ ) {
        if( cy.isNA0(i) ) continue;
        if (classification) {
          int cls = (int)cy.at80(i);
          chk_work(chks,cls).set0(i,1L);
        } else {
          float pred = (float) cy.at0(i);
          chk_work(chks,0).set0(i,pred);
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Build the next random k-trees representing tid-th tree
  private DTree[] buildNextKTrees(Frame fr, int mtrys, float sample_rate, Random rand, int tid) {
    // We're going to build K (nclass) trees - each focused on correcting
    // errors for a single class.
    final DTree[] ktrees = new DTree[_nclass];

    // Initial set of histograms.  All trees; one leaf per tree (the root
    // leaf); all columns
    DHistogram hcs[][][] = new DHistogram[_nclass][1/*just root leaf*/][_ncols];

    // Adjust nbins for the top-levels
    int adj_nbins = Math.max((1<<(10-0)),nbins);

    // Use for all k-trees the same seed. NOTE: this is only to make a fair
    // view for all k-trees
    long rseed = rand.nextLong();
    // Initially setup as-if an empty-split had just happened
    for( int k=0; k<_nclass; k++ ) {
      assert (_distribution!=null && classification) || (_distribution==null && !classification);
      if( _distribution == null || _distribution[k] != 0 ) { // Ignore missing classes
        // The Boolean Optimization cannot be applied here for RF !
        // This optimization assumes the 2nd tree of a 2-class system is the
        // inverse of the first.  This is false for DRF (and true for GBM) -
        // DRF picks a random different set of columns for the 2nd tree.
        //if( k==1 && _nclass==2 ) continue;
        ktrees[k] = new DRFTree(fr,_ncols,(char)nbins,(char)_nclass,min_rows,mtrys,rseed);
        boolean isBinom = classification;
        new DRFUndecidedNode(ktrees[k],-1, DHistogram.initialHist(fr,_ncols,adj_nbins,hcs[k][0],do_grpsplit,isBinom) ); // The "root" node
      }
    }

    // Sample - mark the lines by putting 'OUT_OF_BAG' into nid(<klass>) vector
    Timer t_1 = new Timer();
    Sample ss[] = new Sample[_nclass];
    for( int k=0; k<_nclass; k++)
      if (ktrees[k] != null) ss[k] = new Sample((DRFTree)ktrees[k], sample_rate).dfork(0,new Frame(vec_nids(fr,k),vec_resp(fr,k)), build_tree_one_node);
    for( int k=0; k<_nclass; k++)
      if( ss[k] != null ) ss[k].getResult();
    Log.debug(Sys.DRF__, "Sampling took: + " + t_1);

    int[] leafs = new int[_nclass]; // Define a "working set" of leaf splits, from leafs[i] to tree._len for each tree i

    // ----
    // One Big Loop till the ktrees are of proper depth.
    // Adds a layer to the trees each pass.
    Timer t_2 = new Timer();
    int depth=0;
    for( ; depth<max_depth; depth++ ) {
      if( !Job.isRunning(self()) ) return null;

      hcs = buildLayer(fr, ktrees, leafs, hcs, true, build_tree_one_node);

      // If we did not make any new splits, then the tree is split-to-death
      if( hcs == null ) break;
    }
    Log.debug(Sys.DRF__, "Tree build took: " + t_2);

    // Each tree bottomed-out in a DecidedNode; go 1 more level and insert
    // LeafNodes to hold predictions.
    Timer t_3 = new Timer();
    for( int k=0; k<_nclass; k++ ) {
      DTree tree = ktrees[k];
      if( tree == null ) continue;
      int leaf = leafs[k] = tree.len();
      for( int nid=0; nid<leaf; nid++ ) {
        if( tree.node(nid) instanceof DecidedNode ) {
          DecidedNode dn = tree.decided(nid);
          for( int i=0; i<dn._nids.length; i++ ) {
            int cnid = dn._nids[i];
            if( cnid == -1 || // Bottomed out (predictors or responses known constant)
                tree.node(cnid) instanceof UndecidedNode || // Or chopped off for depth
                (tree.node(cnid) instanceof DecidedNode &&  // Or not possible to split
                 ((DecidedNode)tree.node(cnid))._split.col()==-1) ) {
              LeafNode ln = new DRFLeafNode(tree,nid);
              ln._pred = dn.pred(i);  // Set prediction into the leaf
              dn._nids[i] = ln.nid(); // Mark a leaf here
            }
          }
          // Handle the trivial non-splitting tree
          if( nid==0 && dn._split.col() == -1 )
            new DRFLeafNode(tree,-1,0);
        }
      }
    } // -- k-trees are done
    Log.debug(Sys.DRF__, "Nodes propagation: " + t_3);


    // ----
    // Move rows into the final leaf rows
    Timer t_4 = new Timer();
    CollectPreds cp = new CollectPreds(ktrees,leafs).doAll(fr,build_tree_one_node);
    if (importance) {
      if (classification)   asVotes(_treeMeasuresOnOOB).append(cp.rightVotes, cp.allRows); // Track right votes over OOB rows for this tree
      else /* regression */ asSSE  (_treeMeasuresOnOOB).append(cp.sse, cp.allRows);
    }
    Log.debug(Sys.DRF__, "CollectPreds done: " + t_4);

    // Collect leaves stats
    for (int i=0; i<ktrees.length; i++)
      if( ktrees[i] != null )
        ktrees[i].leaves = ktrees[i].len() - leafs[i];
    // DEBUG: Print the generated K trees
    //printGenerateTrees(ktrees);

    return ktrees;
  }

  // Read the 'tree' columns, do model-specific math and put the results in the
  // fs[] array, and return the sum.  Dividing any fs[] element by the sum
  // turns the results into a probability distribution.
  @Override protected float score1( Chunk chks[], float fs[/*nclass*/], int row ) {
    float sum=0;
    for( int k=0; k<_nclass; k++ ) // Sum across of likelyhoods
      sum+=(fs[k+1]=(float)chk_tree(chks,k).at0(row));
    if (_nclass == 1) sum /= (float)chk_oobt(chks).at0(row); // for regression average per trees voted for this row (only trees which have row in "out-of-bag"
    return sum;
  }

  @Override protected boolean inBagRow(Chunk[] chks, int row) {
    return chk_oobt(chks).at80(row) == 0;
  }

  // Collect and write predictions into leafs.
  private class CollectPreds extends MRTask2<CollectPreds> {
    /* @IN  */ final DTree _trees[]; // Read-only, shared (except at the histograms in the Nodes)
    /* @OUT */ long rightVotes; // number of right votes over OOB rows (performed by this tree) represented by DTree[] _trees
    /* @OUT */ long allRows;    // number of all OOB rows (sampled by this tree)
    /* @OUT */ float sse;      // Sum of squares for this tree only
    CollectPreds(DTree trees[], int leafs[]) { _trees=trees; }
    @Override public void map( Chunk[] chks ) {
      final Chunk    y       = importance ? chk_resp(chks) : null; // Response
      final float [] rpred   = importance ? new float [1+_nclass] : null; // Row prediction
      final double[] rowdata = importance ? new double[_ncols] : null; // Pre-allocated row data
      final Chunk   oobt  = chk_oobt(chks); // Out-of-bag rows counter over all trees
      // Iterate over all rows
      for( int row=0; row<oobt._len; row++ ) {
        boolean wasOOBRow = false;
        // For all tree (i.e., k-classes)
        for( int k=0; k<_nclass; k++ ) {
          final DTree tree = _trees[k];
          if( tree == null ) continue; // Empty class is ignored
          // If we have all constant responses, then we do not split even the
          // root and the residuals should be zero.
          if( tree.root() instanceof LeafNode ) continue;
          final Chunk nids = chk_nids(chks,k); // Node-ids  for this tree/class
          final Chunk ct   = chk_tree(chks,k); // k-tree working column holding votes for given row
          int nid = (int)nids.at80(row);         // Get Node to decide from
          // Update only out-of-bag rows
          // This is out-of-bag row - but we would like to track on-the-fly prediction for the row
          if( isOOBRow(nid) ) { // The row should be OOB for all k-trees !!!
            assert k==0 || wasOOBRow : "Something is wrong: k-class trees oob row computing is broken! All k-trees should agree on oob row!";
            wasOOBRow = true;
            nid = oob2Nid(nid);
            if( tree.node(nid) instanceof UndecidedNode ) // If we bottomed out the tree
              nid = tree.node(nid).pid();                 // Then take parent's decision
            DecidedNode dn = tree.decided(nid);           // Must have a decision point
            if( dn._split.col() == -1 )     // Unable to decide?
              dn = tree.decided(tree.node(nid).pid());    // Then take parent's decision
            int leafnid = dn.ns(chks,row); // Decide down to a leafnode
            // Setup Tree(i) - on the fly prediction of i-tree for row-th row
            //   - for classification: cumulative number of votes for this row
            //   - for regression: cumulative sum of prediction of each tree - has to be normalized by number of trees
            double prediction = ((LeafNode)tree.node(leafnid)).pred(); // Prediction for this k-class and this row
            if (importance) rpred[1+k] = (float) prediction; // for both regression and classification
            ct.set0(row, (float)(ct.at0(row) +  prediction));
            // For this tree this row is out-of-bag - i.e., a tree voted for this row
            oobt.set0(row, _nclass>1?1:oobt.at0(row)+1); // for regression track number of trees, for classification boolean flag is enough
          }
          // reset help column for this row and this k-class
          nids.set0(row,0);
        } /* end of k-trees iteration */
        if (importance) {
          if (wasOOBRow && !y.isNA0(row)) {
            if (classification) {
              int treePred = ModelUtils.getPrediction(rpred, data_row(chks,row, rowdata));
              int actuPred = (int) y.at80(row);
              if (treePred==actuPred) rightVotes++; // No miss !
            } else { // regression
              float  treePred = rpred[1];
              float  actuPred = (float) y.at0(row);
              sse += (actuPred-treePred)*(actuPred-treePred);
            }
            allRows++;
          }
        }
      }
    }
    @Override public void reduce(CollectPreds mrt) {
      rightVotes += mrt.rightVotes;
      allRows    += mrt.allRows;
      sse        += mrt.sse;
    }
  }

  // A standard DTree with a few more bits.  Support for sampling during
  // training, and replaying the sample later on the identical dataset to
  // e.g. compute OOBEE.
  static class DRFTree extends DTree {
    final int _mtrys;           // Number of columns to choose amongst in splits
    final long _seeds[];        // One seed for each chunk, for sampling
    final transient Random _rand; // RNG for split decisions & sampling
    DRFTree( Frame fr, int ncols, char nbins, char nclass, int min_rows, int mtrys, long seed ) {
      super(fr._names, ncols, nbins, nclass, min_rows, seed);
      _mtrys = mtrys;
      _rand = createRNG(seed);
      _seeds = new long[fr.vecs()[0].nChunks()];
      for( int i=0; i<_seeds.length; i++ )
        _seeds[i] = _rand.nextLong();
    }
    // Return a deterministic chunk-local RNG.  Can be kinda expensive.
    @Override public Random rngForChunk( int cidx ) {
      long seed = _seeds[cidx];
      return createRNG(seed);
    }
  }

  @Override protected DecidedNode makeDecided( UndecidedNode udn, DHistogram hs[] ) {
    return new DRFDecidedNode(udn,hs);
  }

  // DRF DTree decision node: same as the normal DecidedNode, but specifies a
  // decision algorithm given complete histograms on all columns.
  // DRF algo: find the lowest error amongst a random mtry columns.
  static class DRFDecidedNode extends DecidedNode {
    DRFDecidedNode( UndecidedNode n, DHistogram hs[] ) { super(n,hs); }
    @Override public DRFUndecidedNode makeUndecidedNode( DHistogram hs[] ) {
      return new DRFUndecidedNode(_tree,_nid, hs);
    }

    // Find the column with the best split (lowest score).
    @Override public DTree.Split bestCol( UndecidedNode u, DHistogram hs[] ) {
      DTree.Split best = new DTree.Split(-1,-1,null,(byte)0,Double.MAX_VALUE,Double.MAX_VALUE,0L,0L,0,0);
      if( hs == null ) return best;
      for( int i=0; i<u._scoreCols.length; i++ ) {
        int col = u._scoreCols[i];
        DTree.Split s = hs[col].scoreMSE(col);
        if( s == null ) continue;
        if( s.se() < best.se() ) best = s;
        if( s.se() <= 0 ) break; // No point in looking further!
      }
      return best;
    }
  }

  // DRF DTree undecided node: same as the normal UndecidedNode, but specifies
  // a list of columns to score on now, and then decide over later.
  // DRF algo: pick a random mtry columns
  static class DRFUndecidedNode extends UndecidedNode {
    DRFUndecidedNode( DTree tree, int pid, DHistogram[] hs ) { super(tree,pid, hs); }

    // Randomly select mtry columns to 'score' in following pass over the data.
    @Override public int[] scoreCols( DHistogram[] hs ) {
      DRFTree tree = (DRFTree)_tree;
      int[] cols = new int[hs.length];
      int len=0;
      // Gather all active columns to choose from.
      for( int i=0; i<hs.length; i++ ) {
        if( hs[i]==null ) continue; // Ignore not-tracked cols
        assert hs[i]._min < hs[i]._maxEx && hs[i].nbins() > 1 : "broken histo range "+hs[i];
        cols[len++] = i;        // Gather active column
      }
      int choices = len;        // Number of columns I can choose from
      assert choices > 0;

      // Draw up to mtry columns at random without replacement.
      for( int i=0; i<tree._mtrys; i++ ) {
        if( len == 0 ) break;   // Out of choices!
        int idx2 = tree._rand.nextInt(len);
        int col = cols[idx2];     // The chosen column
        cols[idx2] = cols[--len]; // Compress out of array; do not choose again
        cols[len] = col;          // Swap chosen in just after 'len'
      }
      assert choices - len > 0;
      return Arrays.copyOfRange(cols,len,choices);
    }
  }

  static class DRFLeafNode extends LeafNode {
    DRFLeafNode( DTree tree, int pid ) { super(tree,pid); }
    DRFLeafNode( DTree tree, int pid, int nid ) { super(tree,pid,nid); }
    // Insert just the predictions: a single byte/short if we are predicting a
    // single class, or else the full distribution.
    @Override protected AutoBuffer compress(AutoBuffer ab) { assert !Double.isNaN(pred()); return ab.put4f((float)pred()); }
    @Override protected int size() { return 4; }
  }

  // Deterministic sampling
  static class Sample extends MRTask2<Sample> {
    final DRFTree _tree;
    final float _rate;
    Sample( DRFTree tree, float rate ) { _tree = tree; _rate = rate; }
    @Override public void map( Chunk nids, Chunk ys ) {
      Random rand = _tree.rngForChunk(nids.cidx());
      for( int row=0; row<nids._len; row++ )
        if( rand.nextFloat() >= _rate || Double.isNaN(ys.at0(row)) ) {
          nids.set0(row, OUT_OF_BAG);     // Flag row as being ignored by sampling
        }
    }
  }

  /**
   * Cross-Validate a DRF model by building new models on N train/test holdout splits
   * @param splits Frames containing train/test splits
   * @param cv_preds Array of Frames to store the predictions for each cross-validation run
   * @param offsets Array to store the offsets of starting row indices for each cross-validation run
   * @param i Which fold of cross-validation to perform
   */
  @Override public void crossValidate(Frame[] splits, Frame[] cv_preds, long[] offsets, int i) {
    // Train a clone with slightly modified parameters (to account for cross-validation)
    DRF cv = (DRF) this.clone();
    cv.genericCrossValidation(splits, offsets, i);
    cv_preds[i] = ((DRFModel) UKV.get(cv.dest())).score(cv.validation); // cv_preds is escaping the context of this function and needs to be DELETED by the caller!!!
  }
}
