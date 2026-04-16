#include <stdio.h>
#include <string.h>
#include <sys/utsname.h>
#include <unistd.h>

int main(int argc, char **argv) {
    struct utsname system_info;
    memset(&system_info, 0, sizeof(system_info));
    uname(&system_info);

    printf("hello-arm64 running\n");
    printf("uid=%d\n", getuid());
    printf("arch=%s\n", system_info.machine[0] ? system_info.machine : "unknown");

    if (argc > 1) {
        for (int i = 1; i < argc; i++) {
            printf("arg[%d]=%s\n", i, argv[i]);
        }
    } else {
        printf("no arguments\n");
    }

    return 0;
}
