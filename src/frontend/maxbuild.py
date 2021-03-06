"""This module contains useful functions and classes for creating and managing MaxCompiler builds"""
import os
import multiprocessing
import subprocess
import re
from multiprocessing import Pool
from termcolor import colored
from os.path import isfile, join
import utils

TARGET_DFE_MOCK = 'dfe_mock'
TARGET_DFE = 'dfe'
TARGET_SIM = 'sim'
DIR_PATH_LOG = 'logs'

class PrjConfig:
  def __init__(self, p, t, n, prj_id, buildRoot):
    self.params = p
    self.target = t
    self.name = n + '_' + str(prj_id)
    self.buildRoot = buildRoot
    self.prj_id = prj_id
    self.runResults = []

  def buildName(self):
    bn = self.name + '_' + self.target
    for k, v in self.params.iteritems():
      bn += '_' + k.replace('_', '') + v
    return bn

  def maxfileName(self):
    return self.name + '.max'

  def __str__(self):
    return 'params = {0}, target = {1}, name = {2}'.format(
        self.params, self.target, self.name)

  def __repr__(self):
    return self.__str__()

  def maxBuildParams(self):
    params = ''
    for k, v in self.params.iteritems():
      params += k + '=' + v + ' '
    return params

  def getParam(self, p):
    return self.params[p]

  def buildTarget(self):
    if self.target == TARGET_SIM:
      return 'DFE_SIM'
    return 'DFE'

  def buildDir(self):
    return os.path.join(self.buildRoot, self.buildName())

  def maxFileLocation(self):
    return os.path.join(self.resultsDir(), self.maxfileName())

  def maxFileTarget(self):
    return os.path.join(self.buildName(), 'results', self.maxfileName())

  def resultsDir(self):
    return os.path.join(self.buildDir(), 'results')

  def libName(self):
    return 'lib{0}.so'.format(self.buildName())

  def sim(self):
    return self.target == TARGET_SIM

  def resourceUsageReportDir(self):
    return os.path.join(self.buildDir(), 'src_annotated')

  def buildLog(self):
    return os.path.join(self.buildDir(), '_build.log')

  def logFile(self):
    return os.path.join(DIR_PATH_LOG, self.buildName() + '.log')

  def getBuildResourceUsage(self):
    usage = {}
    with open(self.buildLog()) as f:
      print self.buildLog()
      while True:
        l = f.readline()
        if l.find('FINAL RESOURCE USAGE') != -1:
          # build output depends on device type, for Altera Stratix use 6
          # lines, for Xilinx use 7 lines
          for i in range(6):
            m = re.match(r'.*PROGRESS:(.*):\s*(\d*)\s/\s(\d*).*', f.readline())
            usage[m.group(1).strip()] = (int(m.group(2)), int(m.group(3)))
          break
    return usage


class MaxBuildRunner:
  def __init__(self, poolSize=6):
    self.poolSize = poolSize

  def runBuilds(self, prjs):
    # run MC builds in parallel
    pool = Pool(self.poolSize)
    for prj in prjs:
      print '  --> Building MaxFile: ', prj.buildName()
      print '               logfile: ', prj.logFile()
    pool.map(runMaxCompilerBuild, prjs)
    pool.close()
    pool.join()

def runMaxCompilerBuild(prj):
  buildParams = "target={0} buildName={1} maxFileName={2} ".format(
      prj.buildTarget(), prj.buildName(), prj.name)

  # We ignore the return code, because if the timing score is negative,
  # the build process fails, but we don't want to stop the compilation
  utils.execute(
      ['make', 'MAX_BUILDPARAMS="' + prj.maxBuildParams() + buildParams + '"',
        "-C", prj.buildRoot, prj.maxFileTarget()],
      prj.logFile())

  utils.execute(['sed', '-i', '-e', r's/PARAM(TIMING_SCORE,.*)/PARAM(TIMING_SCORE, 0)/',
    prj.maxFileLocation()],
    prj.logFile())

  print '  --> Build finished', prj.buildName()
