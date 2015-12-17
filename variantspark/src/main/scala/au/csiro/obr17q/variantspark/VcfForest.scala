package au.csiro.obr17q.variantspark

import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.ml.classification.RandomForestClassifier
import org.apache.spark.ml.classification.RandomForestClassificationModel
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import scala.io.Source
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.ml.feature.{VectorIndexer, StringIndexer}
import org.apache.spark.rdd.RDD
/**
 * @author obr17q
 */
object VcfForest extends SparkApp {
  conf.setAppName("VCF foresting")

  case class Record(individual: String, sampleType: String, bmi: Double, preLabel: String, features: Vector)

  
  def main(args:Array[String]) {
    
    //val args = Array("data/smALL.vcf","5","5","100","auto","0.5")
    
    val seed = 3262362
    val VcfFiles = args(0)
    val NumTrees = args(1).toInt
    //val maxDepth = args(2).toInt

    val FeatureSubsetStrategy = args(4)
    val VariantCutoff = args(5).toInt


    val numFolds = 10
    val maxDepth = Array(5, 10)
    //val maxDepth = Array(5)
    val maxBins = Array(2, 10, 20)
    //val maxBins = Array(10)

    /**
     * TCGA settings
     * 01 - Individual ID
     * 06 - Sex
     * 46 - Weight
     * 47 - Height
     */
    val PopFiles = Source.fromFile("data/nationwidechildrens.org_clinical_patient_coad.txt").getLines()
    val IndividualMeta = sc.parallelize(new MetaDataParser(PopFiles, 3, '\t', "[Not Available]", IndividualIdCol = 1, PopulationCol = 2 )(WeightCol = 46, HeightCol = 47, SexCol = 6).returnBmiMap())

    
    /**
     * TCGA settings
     * 00 - Individual
     * 01 - Population
     * 02 - Super Population
     * 03 - Gender
     */
    //val PopFiles = Source.fromFile("data/ALL.panel").getLines()
    //val IndividualMeta = sc.parallelize(new MetaDataParser(PopFiles, HeaderLines = 1, '\t', "", 0, 2 )(SexCol = 3).returnMap())


    val vcfObject = new VcfParser(VcfFiles, VariantCutoff, sc)

    val NoOfAlleles = vcfObject.variantCount

    val FilteredAlleles = vcfObject.individualTuples

    val IndividualVariants = FilteredAlleles
      .groupByKey //group by individual ID, i.e. get RDD of individuals
      .map(p => (p._1.split('_')(0).substring(0,12), (p._1.split('_')(1), p._2))) // Split the TCGA key to get ID & type
      .filter(_._2._1 == "NORMAL")
    //.map(p => (p._1, (p._1, p._2))) // 1000 data
      .join(IndividualMeta.map(_.toBMI)) //filter out individuals lacking required data
      .map(h =>
      /*
      (h._1, h._2._1._1, h._2._2, LabeledPoint(if (h._2._2 =="EUR") 0
                  else if (h._2._2 =="AFR") 1 else if (h._2._2 =="AMR") 2
                  else if (h._2._2 =="EAS") 3
                  else -1, Vectors.sparse(NoOfAlleles, h._2._1._2.to[Seq] ))) // Binary labels

      (h._1, h._2._1._1, h._2._2, LabeledPoint(if (h._2._2 =="GBR") 0
                  else if (h._2._2 =="ASW") 1 else if (h._2._2 =="CHB") 2
                  else -1, Vectors.sparse(NoOfAlleles, h._2._1._2.to[Seq] ))) // Binary labels
      */            
                  
      (h._1, h._2._1._1, h._2._2, LabeledPoint(if (h._2._2 > 40) 2 else if (h._2._2 > 30) 1.0 else 0.0, Vectors.sparse(NoOfAlleles, h._2._1._2.to[Seq] ) ))
          

          //if (h._2._2 == 1) LabeledPoint(h._2._2, Vectors.sparse(NoOfAlleles, h._2._1._2._2.to[Seq] ))
          //else LabeledPoint(h._2._2, Vectors.sparse(NoOfAlleles, Seq((1,1.0), (2,1.0)) ))
          
      
      )  // Continuous labels
    .cache
    //.map(h => (h._1, h._2, h._3, h._4.label)).collect().foreach(println)

    //val ReducedFeatureSelectedSet: RDD[(String, String, Double, LabeledPoint)] = sc
    //.objectFile("reducedset", 100)
    
    //val DataA = ReducedFeatureSelectedSet.map(p => (p._1, p._2, p._3, LabeledPoint(p._3, p._4.features)))



    val data = sqlContext.createDataFrame(IndividualVariants.map(p => Record(p._1, p._2, p._3, p._4.label.toString, p._4.features))).toDF

    val Array(trainingData, testData) = data.randomSplit(Array(0.7, 0.3))

    val Array(trainingData2, testData2) = data.randomSplit(Array(0.7, 0.3))

    val Array(trainingData3, testData3) = data.randomSplit(Array(0.7, 0.3))

    val labelIndexer = new StringIndexer()
      .setInputCol("preLabel")
      .setOutputCol("label")
      .fit(data)

    //val featureIndexer = new VectorIndexer()
    //  .setInputCol("preFeatures")
    //  .setOutputCol("features")
    //  .setMaxCategories(100)
    //  .fit(data)

    val rf = new RandomForestClassifier()
      .setLabelCol("label")
      .setFeaturesCol("features")
      .setNumTrees(NumTrees)
      .setFeatureSubsetStrategy(FeatureSubsetStrategy)

    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, rf))

    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("label")
      .setPredictionCol("prediction")
      // "f1", "precision", "recall", "weightedPrecision", "weightedRecall"
      .setMetricName("f1")

    val paramGrid = new ParamGridBuilder()
      .addGrid(rf.maxDepth, maxDepth)
      .addGrid(rf.maxBins, maxBins)
      .build

    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(evaluator)
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(numFolds)

    //val model = pipeline.fit(trainingData)







    /**
      * Test the model on the test set.
      * Prints predictions and probabilities.
      */
    /*
    println("Output is:")
    cvModel.transform(testData)
      .select("individual", "sampleType", "label", "probability", "prediction")
      .collect()
      .foreach { case Row(individual: String, sampleType: String, label: Double, prob: Vector, prediction: Double) =>
        println(s"($individual, $sampleType, $label) --> prob=$prob, prediction=$prediction")
      }
    */



    val AlleleTuples = vcfObject.alleleTuples


    def modelFit(df : DataFrame): RDD[(Int, (String, Double))] = {
      val cvModel = cv.fit(df)

      val forestModel = cvModel
        .bestModel
        .asInstanceOf[PipelineModel]
        .stages(1)
        .asInstanceOf[RandomForestClassificationModel]

      val importantFeatures = forestModel.featureImportances

      AlleleTuples
        .filter(p => importantFeatures(p._1) > 0.002 )
        .map(p => (p._1, (p._2, importantFeatures(p._1))) )
    }





    val filteredAlleleTuples1 = modelFit(trainingData)
    val filteredAlleleTuples2 = modelFit(trainingData2)
    val filteredAlleleTuples3 = modelFit(trainingData3)




    //(19:16422392, 0.002136421712119211) (19:16422392, 0.004436421712119211)
    // (2, 19:16422392, 0.006)
    val allOfThem = ( filteredAlleleTuples1 union filteredAlleleTuples2 union filteredAlleleTuples3 )
    .aggregateByKey((0, "", 0.0))((acc, value) =>  (acc._1 + 1, value._1, acc._3 + value._2), (acc1, acc2) => (acc1._1 + acc2._1, acc1._2, acc1._3 + acc2._3))





    allOfThem.map(p => (p._1, p._2._2, p._2._3, p._2._1)).sortBy(_._3).collect.foreach(println)










    //println("classes = " + forestModel.numClasses)
    //println("features = " + forestModel.numFeatures)
    //println("important features = " + filteredAlleleTuples.count)


    //model.transform(testData)
    //  .select("individual", "label", "prediction", "bmi")
    //  .collect()
    //  .foreach(p => println("%s (%s) (%s) predicted %scorrectly. BMI: %s" format(p(0), p(1), p(2), if (p(1).toString == p(2).toString) "" else "in", p(3) )))



    //val (trainingData, testData) = (splits(0), splits(1))
    
    
    
    /**
     * Print a count of healthy and obese individuals
     */
    
    //def isObese(i: Double) = i >= 30
    //def isMale(i: Double) = i == 1
    //println(IndividualVariants.count() + " individuals")
    //println("with " + NoOfAlleles + " alleles")
  
    


    
    /**
     * Strategies
     * For binary classification and regrssion
     */
    
    /*
    val ClassificationStrategy = new Strategy(algo = org.apache.spark.mllib.tree.configuration.Algo.Classification,
                                impurity = org.apache.spark.mllib.tree.impurity.Gini,
                                maxDepth = args(2).toInt,
                                numClasses = 3,
                                maxBins = args(3).toInt,
                                categoricalFeaturesInfo = Map[Int, Int](),
                                maxMemoryInMB = 1024
                                )

    val RegressionStrategy = new Strategy(algo = org.apache.spark.mllib.tree.configuration.Algo.Regression,
                                impurity = org.apache.spark.mllib.tree.impurity.Variance,
                                maxDepth = args(2).toInt,
                                maxBins = args(3).toInt,
                                categoricalFeaturesInfo = Map[Int, Int]()
                               )  

  */
  /**
   * Stuff for binary classifier
   */
    
    /*
  val TestArray: Array[Double] = new Array(10)
  val RandArray: Array[Double] = new Array(10)
    
  var a = 0
  for( a <- 0 to 9){
    val start = new Date().getTime
    val trainingData =
      if (a==0)       splits(1).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(8)).union(splits(9))//0
      else if (a==1)  splits(0).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(8)).union(splits(9))//1
      else if (a==2)  splits(0).union(splits(1)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(8)).union(splits(9))//2
      else if (a==3)  splits(0).union(splits(1)).union(splits(2)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(8)).union(splits(9))//3
      else if (a==4)  splits(0).union(splits(1)).union(splits(2)).union(splits(3)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(8)).union(splits(9))//3
      else if (a==5)  splits(0).union(splits(1)).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(6)).union(splits(7)).union(splits(8)).union(splits(9))//3
      else if (a==6)  splits(0).union(splits(1)).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(7)).union(splits(8)).union(splits(9))//3
      else if (a==7)  splits(0).union(splits(1)).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(8)).union(splits(9))//3
      else if (a==8)  splits(0).union(splits(1)).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(9))//3
      else            splits(0).union(splits(1)).union(splits(2)).union(splits(3)).union(splits(4)).union(splits(5)).union(splits(6)).union(splits(7)).union(splits(8))//4

    val testData = splits(a)






    val model = RandomForest.trainClassifier(
                                             input=trainingData.map(_._4),
                                             ClassificationStrategy,
                                             numTrees = args(1).toInt,
                                             featureSubsetStrategy = args(4),
                                             seed)

    val labelsAndPredictions = testData.map { point =>
      val prediction = model.predict(point._4.features)
      (point._1, point._2, point._4.label, prediction)
    }.map(p => (p._1, p._2, p._3, p._4))


    */

    /**
     * Stuff for regressor
     */
    /*
    val model = RandomForest.trainRegressor(
                                          input = trainingData.map(_._4),
                                          RegressionStrategy,
                                          numTrees = args(1).toInt,
                                          featureSubsetStrategy = args(4),
                                          seed)
*/





    // Evaluate model on test instances and compute test error

    
    //labelsAndPredictions.collect().foreach(println)

    /*
    println("Calculating metrics..")
    
    //// Adjusted Rand Index
    val resultArray: Array[(Double, Double)] = labelsAndPredictions.map(p => (p._3, p._4)).collect
    val clustered = "[%s]".format(resultArray.map(_._1.toString()).reduceLeft(_+","+_))
    val expected = "[%s]".format(resultArray.map(_._2.toString()).reduceLeft(_+","+_))
    val adjustedRandIndex = GetRandIndex(clustered, expected)
    


    //val testMSE = labelsAndPredictions.map(p => (p._3,p._4)).map{ case(v, p) => math.pow((v - p), 2)}.mean()
    val errors = labelsAndPredictions.filter(r => r._3 != r._4).count.toDouble
    val total = testData.count()
    val testErr = errors / total
    println(errors + " wrong out of " + total)
    println("Test Error = " + testErr)
    println("Adjusted Rand Index = " + adjustedRandIndex)
    //println("Learned classification forest model:\n" + model.toDebugString)

    val metrics = new MulticlassMetrics(labelsAndPredictions.map(p => (p._3, p._4)))
    val precision = Array(metrics.precision(0), metrics.precision(1), metrics.precision(2))
    val recall = Array(metrics.recall(0), metrics.recall(1), metrics.recall(2))
    

    println(metrics.confusionMatrix.toString())
    
    
    
    println("GBR - precision:" + precision(0) + " recall:" + recall(0))
    println("ASW - precision:" + precision(1) + " recall:" + recall(1))
    println("CHB - precision:" + precision(2) + " recall:" + recall(2))
    //println("EAS - precision:" + precision(3) + " recall:" + recall(3))
    
    
    TestArray(a) = testErr
    RandArray(a) = adjustedRandIndex.toDouble
    val end = new Date().getTime
    println("Job took "+(end-start)/1000 + " seconds")

    //val metrics = new MulticlassMetrics(labelsAndPredictions.map(p => (p._3, p._4)))
    //val precision = Array(metrics.precision(0), metrics.precision(1), metrics.precision(2), metrics.precision(3))
    //val recall = Array(metrics.recall(0), metrics.recall(1), metrics.recall(2), metrics.recall(3))
    }  
    //println("EUR - precision:" + precision(0) + " recall:" + recall(0))
    //println("AFR - precision:" + precision(1) + " recall:" + recall(1))
    //println("AMR - precision:" + precision(2) + " recall:" + recall(2))
    //println("EAS - precision:" + precision(3) + " recall:" + recall(3))

  
  
  
    println("Test errors: " + TestArray.mkString(", "))
    println("Adjusted Rand Indices: " + RandArray.mkString(", "))
    */
    
    /*
    val selector = new ChiSqSelector(50000)
    val transformer = selector.fit(SparseVariants.map(_._4))
    val filteredData = SparseVariants.map { lp => 
      LabeledPoint(lp._4.label, transformer.transform(lp._4.features)) 
    }

    val featuresThatAreSelected = transformer.selectedFeatures.map(a => print(a, ","))




    */
    
    
    
  }
}