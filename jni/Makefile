WFLAGS = -Wall -Wextra -Werror -Wno-unused-parameter -Wno-pointer-to-int-cast
CFLAGS = ${WFLAGS} -std=c99 -O3

all: runtests
	ndk-build
	./genAndroSSNative.pl

tests: tests.c
	gcc ${CFLAGS} -o tests tests.c

runtests: tests
	@./tests
	@echo "All tests completed successfully."

clean:
	rm -f tests

