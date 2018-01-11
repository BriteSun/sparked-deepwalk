package com.nrl

import org.apache.spark.sql._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.HashPartitioner
import scala.util.Random
import java.io._


object SparkedDeepWalkApp {

    def main(args: Array[String]) {

      val spark = SparkSession
                    .builder()
                    .appName("Sparked DeepWalk")
                    .getOrCreate()

      import spark.implicits._

      Logger.getRootLogger().setLevel(Level.ERROR)

      val DATASET_NAME  = args(1)
      val DATASET_DIR   = args(2) 
      val NODES_FILE    = args(3) 
      val EDGES_FILE    = args(4) 
      val LABELS_FILE   = args(5) 
      val NODE_TAG_FILE = args(6) 
      val OUTPUT_DIR    = args(7) 

      val edges = spark.read.textFile(DATASET_DIR + EDGES_FILE).rdd
                       .flatMap { line => {
                            val fields = line.split(",")
                            val a = fields(0).toLong
                            val b = fields(1).toLong
                            Array((a,b), (b,a))
                         }
                       }

      val nodes  = spark.read.textFile(DATASET_DIR + NODES_FILE).rdd.map(_.toLong)
      val labels = spark.read.textFile(DATASET_DIR + LABELS_FILE).rdd.map(_.toLong)

      val adjacencyList = edges.groupByKey()
                               .mapValues(_.toArray)
                               .partitionBy(new HashPartitioner(100))
                               .persist()


      val LAMBDA = 10

      var keyedRandomWalks = adjacencyList.keys.map(id => (id, List(id)))
      
      for (iter <- 1 until LAMBDA) {
        keyedRandomWalks = adjacencyList.join(keyedRandomWalks)
                        .map {
                          case (_, (neighbours, walkSoFar)) => {
                            val r = new Random
                            val randomNeighbour = neighbours(r.nextInt(neighbours.size))
                            (randomNeighbour, randomNeighbour :: walkSoFar )
                          } 
                        }

      }

      val randomWalks = keyedRandomWalks.values.persist()

      val degreeDistribution = randomWalks.flatMap((walk: List[Long]) => walk)
                                          .countByValue
                                          .values
                                          .groupBy(identity)
                                          .mapValues(_.size)

      val writer = new PrintWriter(new File(OUTPUT_DIR + "degree_dist.csv"))
      writer.write("degree,frequency\n")
      degreeDistribution.foreach {
        case (k, v) => 
          writer.write(k +","+ v+"\n")
      }
      writer.close()

      println(DATASET_NAME)
      println("|V| " + nodes.count)
      println("|E| " + edges.count)
      println("|Y| " + labels.count)
      println("Adjacency List |V|" + adjacencyList.count)
      println("Random Walk |V|" + randomWalks.count)

      spark.stop()

    }
}
