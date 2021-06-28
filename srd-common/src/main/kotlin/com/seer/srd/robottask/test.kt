package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.robottask.RobotTaskDef
import com.seer.srd.robottask.RobotTaskHttpApiDef
import com.seer.srd.robottask.RobotTransportDef
import com.seer.srd.robottask.updateRobotTaskDefs
import org.bson.types.ObjectId
import org.jetbrains.annotations.TestOnly
import kotlin.collections.forEach as forEach1


@TestOnly
fun main(){
    val robotid1 = Robotid(1)
    val robotid2 = Robotid(1)
    val robotid3 = Robotid(2)
    val robotid4 = Robotid(3)
    val robotid5 = Robotid(3)
    val robotid6 = Robotid(1)

    val numbers = mutableListOf(robotid1,robotid2,robotid3,robotid4,robotid5,robotid6)
    updateRobotTaskDefs1(numbers);
}
fun updateRobotTaskDefs1(defs: MutableList<Robotid>){
//    val distinctBy = defs.distinctBy { it }
//    println("distinctBy"+distinctBy)
//    //distinctBy.forEach1 { it -> distinctBy.remove(it) }
//    println(defs)

//    println("===============")
//    defs.distinctBy { it }.forEach1 { it -> defs.remove(it) }
//    println(defs)
//    if (defs.isNotEmpty()) throw BusinessError("""导入数据存在相同任务标识: "${defs[0]}"""")

}
data class Robotid(
    var id: Int

)
