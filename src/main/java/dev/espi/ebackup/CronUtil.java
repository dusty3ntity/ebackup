package dev.espi.ebackup;

import it.sauronsoftware.cron4j.Predictor;
import it.sauronsoftware.cron4j.SchedulingPattern;

import java.util.Date;

/*
   Copyright 2020 EspiDev

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

public class CronUtil {
    static Predictor predictor;
    static Date nextExecution;

    public static void checkCron() {
        String expression = eBackup.getPlugin().crontask;
        SchedulingPattern scheduler = new SchedulingPattern(expression);
        scheduler.validate(expression);

        eBackup.getPlugin().getLogger().info("Configured the cron task to be: " + scheduler);

        predictor = new Predictor(expression);
        nextExecution = predictor.nextMatchingDate();
    }

    public static boolean run() {
        Date now = new Date();
        if (nextExecution.before(now)) {
            nextExecution = predictor.nextMatchingDate();
            return true;
        } else {
            return false;
        }
    }
}
