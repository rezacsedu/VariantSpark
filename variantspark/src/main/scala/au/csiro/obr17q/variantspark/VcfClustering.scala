package au.csiro.obr17q.variantspark

import au.csiro.obr17q.variantspark.model.VcfParser
import au.csiro.obr17q.variantspark.model.GenericVcfParser
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.rdd.RDD
import org.apache.spark.ml.clustering.KMeans

import scala.io.Source

object VcfClustering extends SparkApp {
  conf.setAppName("VCF cluster")
  def main(args:Array[String]) {

    val HOME = if (masterUrl == "local") "/Users/obr17q/" else "/OSM/HOME-CDC/obr17q/"
    val args1 = {
      if (masterUrl == "local") {
        /*
         * Define offline variables here!!!
         */
        Array(
            "data/merged.vcf", //Input VCF file
            "3",             //Number of clusters (k)
            "",   //Groups for inclusion
            "",   //Groups for exclusion
            "0.1"            //Sample size (0 - 1)
        )
      } else {
      args
      }
    }
    if (args1.length < 1) {
        println("Usage: CsvClusterer <input-path>")
    }

    val VcfFiles = args1(0)
    val k = args1(1).toInt
    // val IncludeGroups = args1(2).split('|')
    // val ExcludeGroups = args1(3).split('|')
    // val VariantCutoff = args1(4).toInt
    val VariantCutoff = 0
    val LABEL_NAME = "superPopulation" 
    
    
    
    
    // val PopFiles = Source.fromFile("data/PGPParticipantSurvey-20150831064509.csv").getLines()
    // val Populations = sc.parallelize(new PopulationMap(PopFiles, 1, ',', 0, 16 ).returnMap(IncludeGroups, ExcludeGroups))

    // val PopFiles = Source.fromFile("data/ALL.panel").getLines()
    // val IndividualMeta : RDD[IndividualMap] = sc.parallelize(new MetaDataParser(PopFiles, 1, '\t', "NA", 0, 1 ).returnMap(IncludeGroups, ExcludeGroups))
    val IndividualMeta : RDD[IndividualMap] = null
    val vcfObject = new GenericVcfParser(VcfFiles, VariantCutoff, IndividualMeta, sc, sqlContext)

    val FilteredAlleles = vcfObject.individualTuples



    /**
     * Vector of elements for each individual
     * Vector elements are zipped with the Individual ID
     */
    //val IndividualVariants = FilteredAlleles
    //.groupByKey //group by individual ID, i.e. get RDD of individuals
    //.join(Populations.map(_.toPops)) //filter out population groups you don't want
    //.map(h => (h._1, h._2._2, Vectors.sparse(NoOfAlleles, h._2._1.to[Seq] )))//.cache() //create sparse vectors

    val data = vcfObject.data


    /** Print populations included and number of individuals (slow) **/
    //IndividualVariants.map(p => (p._1, 1)).join(Populations.map(_.toPops)).map(p => (p._2._2, 1)).reduceByKey(_ + _).collect().foreach(println)

    /** Print the count of variants for each individual **/
    //val countt = SparseVariants.map(p => (p._2.toArray).reduce(_ + _)).collect().foreach(println)

    //Populations.collect().foreach(println)
    //println("Processed VCF file with %s variants.".format(NoOfAlleles))
    
    val pEndTime = System.currentTimeMillis()






    //// val SparVecs = rows
    //val mat: RowMatrix = new RowMatrix(SparVecs.map(_._2))
    //// Compute similar columns perfectly, with brute force.
    //val exact = mat.columnSimilarities()
    //// Compute similar columns with estimation using DIMSUM
    //val approx = mat.columnSimilarities(0.1)
    //val exactEntries = exact.entries.map { case MatrixEntry(i, j, u) => ((i, j), u) }
    //val approxEntries = approx.entries.map { case MatrixEntry(i, j, v) => ((i, j), v) }
    //val MAE = exactEntries.leftOuterJoin(approxEntries).values.map {
    //  case (u, Some(v)) =>
    //    math.abs(u - v)
    //  case (u, None) =>
    //    math.abs(u)
    //}.mean()
    //println(s"Average absolute error in estimate is: $MAE")


     //val dataFrame = SparseVariants.map(p => (p._2) )


    //val SparseVariants: RDD[(String, String, Vector)] = sc.objectFile("/flush/obr17q/phase3RDD")
    
    val dataFrame = data.cache()
    //val dataFrame: RDD[Vector] = sc.objectFile("/flush/obr17q/genomeRDD-chr22").cache()



    /*
    val writer = new PrintWriter(HOME + "pgp.json", "UTF-8")
    val m2JSONArray = new JSONArray()
    dataFrame.collect().foreach(p => {
      val mJSONArray = new JSONArray(p._2.toArray)
      val mJSONObject = new JSONObject()
      mJSONObject.put("variants", mJSONArray)
      mJSONObject.put("userID", p._1)
      m2JSONArray.put(mJSONObject)
    })
    writer.println( m2JSONArray )
    writer.close()
    */





    val labelIndexer = new StringIndexer()
      .setInputCol(LABEL_NAME)
      .setOutputCol("label")
      .fit(data)

    val kmeans = new KMeans()
      .setK(k)
      .setPredictionCol("prediction")
      .setFeaturesCol("features")

    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, kmeans))




    val model = kmeans.fit(data)


    val predictions = model.transform(data)

    val predictionsAndLabels = predictions.select("prediction", "individual")
      .map(row => (row.getInt(0), row.getString(1)))

    predictionsAndLabels.collect.sortBy(_._1).foreach(println)

    
    
    //val WSSSE = model.computeCost(dataFrame.map(_._3))
    
    /** predictions = RDD(IndividualID, DistanceFromCenter, Centroid) **/
    //val predictions = IndividualVariants.map(p => {
    //  (p._1, Vectors.sqdist(p._3, model.clusterCenters(model.predict(p._3))), model.predict(p._3) )
    //})
     
    //val SuperPopulationUniqueId = Populations.map(_.SuperPopulationId).distinct().zipWithIndex() //For ARI
    //val SuperPopulationUniqueId = Populations.map(p => (p._2, p._1)).distinct()
    
    
    // Build RDD of tuples of predictions
    //val predVsExpec = predictions
    //.map(p => (p._1, p._3)) // (IndividualID, Centroid)
    //.join(Populations.map(_.toIndoAll)) // (IndividualID, (Centroid, (PopulationId, SuperPopulationId, something, something)))
    //.map(p => (p._2._2._2, (p._1, p._2._1, p._2._2._3, p._2._2._4, p._2._2._5, p._2._2._6) )) // (PopulationName, (IndividualID, Centroid))
    //.join(SuperPopulationUniqueId) // (PopulationName, ((IndividualID, Centroid), PopulationID))
    //.map(p => (p._2._1._1, p._2._1._2, p._2._2, p._1, p._2._1._3,p._2._1._4,p._2._1._5,p._2._1._6)) // (IndividualID, Centroid, PopulationID, PopulationName)

    
    //.sortBy(_._2, true, 1)
    //.collect()
    
    //predVsExpec.foreach( p => println( "%s: %s - %s, %s, [%s, %s, %s, %s]".format(p._1, p._2, p._4, p._5, p._6, p._7, p._8, p._9) ))
    //predVsExpec.foreach( p => println( "%s: %s - %s, [%s, %s, %s, %s]".format(p._1, p._2, p._4, p._5, p._6, p._7, p._8) ))

    // Find the Adjusted Rand Index.
    // Must have Python and module Scikit installed. 

    
    //val pythonPath ="/Library/Frameworks/Python.framework/Versions/2.7/bin/python"
    //val clustered = "[%s]".format(predVsExpec.map(_._2.toString()).reduceLeft(_+","+_))
    //val expected = "[%s]".format(predVsExpec.map(_._3.toString()).reduceLeft(_+","+_))
    //val adjustedRandIndex = GetRandIndex(clustered, expected)
    
    //println("Metrics:")
    //println("Pre-processing time: %s seconds".format((pEndTime - pStartTime)/1000.0))
    //println("k-Means time: %s seconds".format((kEndTime - kStartTime)/1000.0))
    //println("Within Set Sum of Squared Errors = %s".format(WSSSE))
    //println("Adjusted Rand Index: %s".format(adjustedRandIndex))
    //println("From %s alleles".format(NoOfAlleles))
    
    
    // Save 'predictions' as a SIF file
    //val f = File("my-test.txt")
    //val siffy = predictions.map(p => (f"${p._1}\t${p._2}\t${p._3}"))
    //siffy.coalesce(1).saveAsTextFile("the.sif")

    
    //println(sqdist(clusters.clusterCenters(0),clusters.clusterCenters(1)))
    //println(sqdist(clusters.clusterCenters(0),clusters.clusterCenters(2)))
    //println(sqdist(clusters.clusterCenters(1),clusters.clusterCenters(2)))
    //println(    clusters.clusterCenters(1) )
    //val m = mat.numRows()
    //val n = mat.numCols()
    //println("Rows (m): " + m)
    //println("Cols (n): " + n)
    //val variants = rows.map(e => Row(e:_*))

    

    
  } 
}
