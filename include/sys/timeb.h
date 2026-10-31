#ifndef UAE_ANDROID_SYS_TIMEB_H
#define UAE_ANDROID_SYS_TIMEB_H

#if defined(__ANDROID__)
#include <sys/time.h>
#include <time.h>

struct timeb {
	time_t time;
	unsigned short millitm;
	short timezone;
	short dstflag;
};

static inline int ftime(struct timeb *value)
{
	struct timeval now;
	if (gettimeofday(&now, 0) != 0)
		return -1;
	value->time = now.tv_sec;
	value->millitm = (unsigned short)(now.tv_usec / 1000);
	value->timezone = 0;
	value->dstflag = 0;
	return 0;
}
#else
#include_next <sys/timeb.h>
#endif

#endif
