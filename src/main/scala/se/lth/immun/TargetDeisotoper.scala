package se.lth.immun

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Queue
import scala.collection.mutable.HashSet

import akka.actor._

import se.lth.immun.chem.Peptide
import se.lth.immun.chem.Constants
import se.lth.immun.chem.IsotopeDistribution

class TargetDeisotoper(val params:DinosaurParams) extends Actor with Timeable {

	import Cluster._
	
	implicit val p = params
	
	val toProcess 			= new Queue[(Int, Seq[Target])]
	val beingProcessed 		= new HashSet[Int]
	val completedPatterns	= new ArrayBuffer[Seq[IsotopePattern]]
	
	var hillsByMz:Array[Hill] = _
	var hillsMzs:Array[Double] = _
	var hillsScanIndices:Array[(Int, Int)] = _
	var specTime:Seq[Double] = _
	var customer:ActorRef = _
	
	val targetBatchSize = 1000
	
	def receive = {
		case TargetDeisotope(hs, targets, st) => 
			timer.start
			
			/*
			 * Using a list for the hills is extremely slow for many hills
			 * an array is asympotically faster
			 */
			val hills = hs.toArray
			specTime = st
			customer = sender
			
			println("=== IN TARGETED MODE - BEWARE!!! ===")
		  hillsByMz = hills.sortBy(_.total.centerMz)
		  hillsMzs = hillsByMz.map(h => h.total.centerMz)
		  hillsScanIndices = hillsByMz.map(h => (h.scanIndex.head, h.scanIndex.last))
		  
		  if (params.verbose)
			  println("deisotoping based on targets...")
			
      val batchTargetList = targets.grouped(targetBatchSize).toList
      for (i <- 0 until batchTargetList.length)
			  toProcess += i -> batchTargetList(i)
			
			if (params.verbose)
  			println("created " + batchTargetList.length + " target deisotoping batches")
			processIfFree
		
		case Deisotoped(batchId, isotopes) =>
		  completedPatterns += isotopes
		  beingProcessed -= batchId
		  if (params.verbose)
  		  println("batch deisotoping finished " + batchId)	
		  
		  if (toProcess.isEmpty && beingProcessed.isEmpty) {
				if (params.verbose)
				  println("deisotoping finished")
				
				val isotopes = completedPatterns.flatten.sortBy(ip => (ip.hills.head.total.centerMz, ip.z, ip.apexHill.accurateApexRt(specTime)))
				if (params.verbose)
				  println("sorting finished, " + isotopes.length + " isotope patterns found")
				
				val uniqueIsotopes = isotopes.foldRight(List.empty[IsotopePattern]){ 
          case (a, b) => 
            if (!b.isEmpty && b(0).hills.head.total.centerMz == a.hills.head.total.centerMz && b(0).z == a.z && b(0).apexHill.accurateApexRt(specTime) == a.apexHill.accurateApexRt(specTime)) 
              b 
            else 
              a :: b 
        }
        if (params.verbose)
				  println("filtered unique isotope patterns, " + uniqueIsotopes.length + " isotope patterns remaining")
				
				customer ! TargetDeisotopingComplete(Nil, uniqueIsotopes.toSeq)
				context.stop(self)
			} else
			  processIfFree
						
	}
	
	
	def processIfFree = {
		while (toProcess.nonEmpty && beingProcessed.size < params.concurrency) {
			val (batchId, batchTargets) = toProcess.dequeue
			beingProcessed += batchId
			val a = context.actorOf(Props(new TargetBatchDeisotoper(params)))
  		a ! BatchDeisotope(batchId, hillsByMz, batchTargets, hillsMzs, hillsScanIndices, specTime)
		}
	}
}

class TargetBatchDeisotoper(val params:DinosaurParams) extends Actor {
  
  import Cluster._
  
  implicit val p = params
  
  def receive = {
		
		case BatchDeisotope(batchId, hillsByMz, batchTargets, hillsMzs, hillsScanIndices, specTime) =>
		  val (_, isotopes) = deisotope(hillsByMz, batchTargets, hillsMzs, hillsScanIndices, specTime)
		  
		  sender ! Deisotoped(batchId, isotopes)
		  context.stop(self)
  }
  
	def deisotope(hillsByMz:Array[Hill],
			targets:Seq[Target],
			hillsMzs:Array[Double],
			hillsScanIndices:Array[(Int, Int)],
			specTime:Seq[Double]			
	):(Seq[Seq[Cluster.Edge]], Seq[IsotopePattern]) = {
		
		val targetPatterns = 
			for (t <- targets) yield {
				val monoisoHills = closeHills(hillsByMz, t, hillsMzs, specTime)
				if (monoisoHills.nonEmpty) {
					val patterns = getPatterns(monoisoHills, hillsByMz, t, hillsMzs, hillsScanIndices).map(ip =>
						IsotopePattern(ip.inds.map(hillsByMz), ip.offset, ip.mostAbundNbr - ip.offset, ip.z, ip.averagineCorr))
					
					(t, patterns)
				} else
					(t, Nil)
			}
		
		(Nil, targetPatterns.flatMap(_._2))
	}
	
	
	def closeHills(hills:Array[Hill], t:Target, hillsMzs:Array[Double], specTime:Seq[Double]):Seq[Int] = {
	  val (hillsStartIndx, hillsEndIndx) = DinoUtil.getMinxMaxIndx(hillsMzs, t.mz, t.mzDiff)
		val inds = new ArrayBuffer[Int]
		for (i <- hillsStartIndx until hillsEndIndx) {
			val h = hills(i)
			val hApexRt = h.accurateApexRt(specTime)
			if (hApexRt > t.rtStart && hApexRt < t.rtEnd)
				inds += i
		}
		inds
	}
	
	
	
	def getPatterns(
			seeds:Seq[Int], 
			hills:Array[Hill],
			t:Target,
			hillsMzs:Array[Double],
			hillsScanIndices:Array[(Int, Int)]
	):Seq[IsotopePatternInds] = {
		if (seeds.isEmpty) return Nil
		val patterns = 
			for {
				seed <- seeds
				isopatInds <- extendSeed(seed, Nil, hills, t.z, hillsMzs, hillsScanIndices)
			} yield (isopatInds)
		if (patterns.isEmpty) Nil
		else 
			patterns.filter(ip => ip.inds.head == ip.seed)
	}
	
	
	def extendSeed(seed:Int, inds:Seq[Int], hills:Array[Hill], z:Int, hillsMzs:Array[Double], hillsScanIndices:Array[(Int, Int)]):Option[IsotopePatternInds] = {
		
		val seedTot = hills(seed).total
			
		def extend2(dir:Int):Seq[Int] = {
			var ii = seed+dir
			var nIso = 1
			val sHill = hills(seed)
			val sTot = sHill.total
			var m = sTot.centerMz + dir * nIso * DinoUtil.ISOTOPE_PATTERN_DIFF / z
			var seedErr2 = sTot.centerMzError * sTot.centerMzError
			var isoMissing = false
			val isos = new ArrayBuffer[Int]
			val alts = new ArrayBuffer[Int]
			
			val massErrorSq = params.adv.deisoSigmas * params.adv.deisoSigmas * (
					  2.5*seedErr2)
		  val err2 = DinoUtil.SULPHUR_SHIFT * DinoUtil.SULPHUR_SHIFT / (z*z) + massErrorSq
			val err = Math.sqrt(err2)
			updateHillIdx
			
			def updateHillIdx = {
  			val (minIdx, maxIdx) = DinoUtil.getMinxMaxIndx(hillsMzs, m, err)
	  		ii = if (dir > 0) minIdx else maxIdx
	  		alts ++= (minIdx until maxIdx).filter(i => (math.min(hillsScanIndices(i)._2, sHill.scanIndex.last) > math.max(hillsScanIndices(i)._1, sHill.scanIndex.head)))
	    }
			
			def evalAlts = {
				if (alts.nonEmpty) {
					val corrs = alts.map(a => (a,params.adv.deisoCorrCalc(hills(seed), hills(a))))
					val maxCorr = corrs.maxBy(_._2)
					alts.clear
					if (maxCorr._2 >= params.adv.deisoCorr) {
						isos += maxCorr._1
						nIso += 1
						m = seedTot.centerMz + dir * nIso * DinoUtil.ISOTOPE_PATTERN_DIFF / z
						updateHillIdx
					} else 
						isoMissing = true
				} else
					isoMissing = true
			}
			
			while (!isoMissing) {
			  evalAlts
			}
			evalAlts
			isos
		}
					
		val upMatches = extend2(1)
		//val downMatches = extend2(-1)
		val downMatches = Nil
		
		val result = downMatches.reverse ++ (seed +: upMatches)
		val resSeedInd = downMatches.length
		val resultProfile = result.map(hills(_).apex.intensity)
		
		val minima = DinoUtil.localMinima(resultProfile, params.adv.deisoValleyFactor)
		val oneMaxResult = 
			if (minima.nonEmpty) {
				val lower = minima.filter(_ < resSeedInd).lastOption.getOrElse(0)
				val upper = minima.filter(_ > resSeedInd).headOption.getOrElse(result.length)
				result.slice(lower, upper + 1)
			} else result
		
		val cleanResult =
			if (z * seedTot.centerMz < 1000) {
				val apex = oneMaxResult.maxBy(hills(_).total.intensity)
				oneMaxResult.drop(oneMaxResult.indexOf(apex))
			} else oneMaxResult
		
		val cleanProfile = cleanResult.map(hills(_).total.intensity)
		val avgIsotopeDistr = Peptide.averagine((seedTot.centerMz - Constants.PROTON_WEIGHT)*z).getIsotopeDistribution()
		
		val alignment = params.adv.deisoAveragineStrategy(cleanProfile, avgIsotopeDistr, params)
		
		alignment.map(a => IsotopePatternInds(
				cleanResult,//.drop(math.max(0, -a.offset)), 
				0, //math.max(0, a.offset), 
				0, //avgIsotopeDistr.intensities.indexOf(avgIsotopeDistr.intensities.max), 
				z, 
				a.corr,
				seed
			))
	}
}

case class BatchDeisotope(batchId:Int, hillsByMz:Array[Hill], batchTargets:Seq[Target], hillsMzs:Array[Double], hillsScanIndices:Array[(Int, Int)], specTime:Seq[Double])
case class Deisotoped(batchId:Int, isotopes:Seq[IsotopePattern])
case class TargetDeisotope(hills:Seq[Hill], targets:Seq[Target], specTime:Seq[Double])
case class TargetDeisotopingComplete(clusters:Seq[Seq[Cluster.Edge]], patterns:Seq[IsotopePattern])
