# IPC
Osporject
setting middle ware
#
-docker run -d --name redis-demo -p 6379:6379 redis:7 redis-server --requirepass 12345

-docker exec -it redis-demo redis-cli -a 12345 ping (redis-cli run on container out put "PONG")

-setting acess open powershell as admin then run:

New-NetFirewallRule -DisplayName "Redis 6379 Inbound (Private)" `

-Direction Inbound -LocalPort 6379 -Protocol TCP -Action Allow -Profile Private

-run for IPV4 wifi  :ipconfig

-run this in other cpu :Test-NetConnection 192.168.1.10 -Port 6379 

//chahge 192.168.1.10 to your IP middle ware run in regular powershell Tcp:True mean it work

finish setting middle ware








on manchine A is sub it has to have docker desk top
docker run -it --rm redis:7 redis-cli -h 192.168.1.98 -p 6379 -a 12345 ping //connect to middle U can change 192.168.1.98 
docker run -it --rm redis:7 redis-cli -h 192.168.1.98 -a 12345 SUBSCRIBE broadcast // as rule to be sub


on manchine B is pub 
docker run -it --rm redis:7 redis-cli -h 192.168.1.98 -p 6379 -a 12345 ping //connect to middle 
docker run -it --rm redis:7 redis-cli -h 192.168.1.98 -a 12345 PUBLISH broadcast "hello from Bank notebook to sub"


4. do this on A B C device 

-mkdir redis-pubsub-demo
cd redis-pubsub-demo
mkdir -p src\main\java\pubsub

-then dowload  gradel 
-Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
irm get.scoop.sh | iex // on power shell 

-scoop install gradle

-gradle -v

4.3 src/main/java/pubsub/NodeApp.java 



java -jar build\libs\redis-pubsub-demo-all.jar --host 192.168.1.98 --port 6379 --name node-A --pass 12345 //U can change name 



edit code you have to .\gradlew.bat clean shadowJar beforerun it 

how boss kill
docker run --rm redis redis-cli -h 192.168.1.98 -a 12345 PUBLISH control "shutdown:node-B"






