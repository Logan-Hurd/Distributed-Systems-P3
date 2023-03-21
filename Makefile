JFLAGS = -g
JC = javac
JAVA = java

.SUFFIXES: .java .class

.java.class:
	$(JC) $(JFLAGS) $*.java

default: classes

classes: src/**/*.java
	$(JC) $(JFLAGS) src/**/*.java

test: classes
	# clear serialized state
	$(RM) src/resources/loginData.ser
	# start server in the background on hardcoded port 5180, dying after a 10 second timeout
	timeout 10 $(JAVA) src.Server.IdServer --numport 5180 &
	# wait for server to be ready
	sleep 3
	# execute tests
	$(JAVA) src.Client.IdClientTesting
	# for convenience, wait to exit until background server is guaranteed dead by timeout
	sleep 7

clean:
	$(RM) src/**/*.class
	$(RM) src/resources/loginData.ser

wipe:
	$(RM) src/resources/loginData.ser
