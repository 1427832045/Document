{"TaskDefTakeMat":{"id":null,"name":"TaskDefTakeMat","description":"取料","httpApiList":[{"path":"/ext/wms/black/order/take","method":"POST","reqDemoBody":"{\"wallID\": \"A\", \"indexInWall\": \"1\", \"systemId\": \"sort001\", \"row\": 1, \"column\": 1}","successResponseCode":201,"responseDecorator":"custom","components":[{"def":"TrimRequired","params":{"value":"=httpBody.wallID","message":"缺少任务参数【wallID】！"},"returnName":"wallID","components":[]},{"def":"TrimRequired","params":{"value":"=httpBody.indexInWall","message":"缺少任务参数【indexInWall】！"},"returnName":"indexInWall","components":[]},{"def":"TrimRequired","params":{"value":"=httpBody.systemId","message":"缺少任务参数【systemId】！"},"returnName":"systemId","components":[]},{"def":"TrimRequired","params":{"value":"=httpBody.row","message":"缺少任务参数【row】！"},"returnName":"row","components":[]},{"def":"TrimRequired","params":{"value":"=httpBody.column","message":"缺少任务参数【column】！"},"returnName":"column","components":[]},{"def":"BuildAndPersistReturnBody","params":{"wallId":"=wallID","indexInWall":"=indexInWall","systemId":"=systemId"},"returnName":"returnBody","components":[]},{"def":"SetTaskExtraVariables","params":{"name":"siteId","value":"=\"L-\" + row + \"-\" + column"},"returnName":null,"components":[]},{"def":"CheckExistedStoreSiteById","params":{"siteId":"=siteId"},"returnName":"siteTemp","components":[]},{"def":"SetLocationName","params":{"destination":"=start","value":"=task.persistedVariables.siteId"},"returnName":null,"components":[]},{"def":"SetLocationName","params":{"destination":"=manual","value":"Manual"},"returnName":null,"components":[]},{"def":"SetLocationName","params":{"destination":"=end","value":"=task.persistedVariables.siteId"},"returnName":null,"components":[]},{"def":"SetLocationName","params":{"destination":"=tellWms","value":"=task.persistedVariables.siteId"},"returnName":null,"components":[]}]}],"transports":[{"refName":"","description":"取料","category":"","seqGroup":"","stages":[{"refName":"","description":"锁定目标工位","forRoute":false,"operation":"","location":"","properties":"[]","maxRetries":null,"retryDelay":null,"components":[{"def":"LockSiteOnlyIfNotLock","params":{"siteId":"=start.location"},"returnName":null,"components":[]}]},{"refName":"start","description":"去目标工位","forRoute":true,"operation":"WaitKey","location":"","properties":"[{\"key\":\"3\",\"value\":\"true\"}]","maxRetries":null,"retryDelay":null,"components":[]},{"refName":"manual","description":"去人工工位","forRoute":true,"operation":"WaitKey","location":"","properties":"[{\"key\":\"3\",\"value\":\"true\"}]","maxRetries":null,"retryDelay":null,"components":[]},{"refName":"end","description":"返回目标工位","forRoute":true,"operation":"WaitKey","location":"","properties":"[{\"key\":\"3\",\"value\":\"true\"}]","maxRetries":null,"retryDelay":null,"components":[]},{"refName":"tellWms","description":"告知WMS任务结束","forRoute":true,"operation":"Wait","location":"","properties":"[]","maxRetries":null,"retryDelay":null,"components":[{"def":"RequestRemoteByPost","params":{"description":"告知WMS任务结束","url":"http://localhost:7100/api/ext/sim/wms/black/order/finished","reqBody":"=task.persistedVariables.returnBody"},"returnName":"res","components":[]}]},{"refName":"","description":"解锁目标工位","forRoute":false,"operation":"","location":"","properties":"[]","maxRetries":null,"retryDelay":null,"components":[{"def":"UnlockSiteIfLocked","params":{"siteId":"=start.location"},"returnName":null,"components":[]}]}]}],"static":false,"parallel":false,"noTransport":false}}