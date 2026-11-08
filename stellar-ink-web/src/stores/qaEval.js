import { defineStore } from 'pinia'
import { request } from '@/api/client'

const ROOT = '/ai/admin/eval/qa'

export const useQaEvalStore = defineStore('qa-eval', {
  state: () => ({
    run: null,
    running: false,
    stopRequested: false,
    inFlightCaseId: '',
    selectedCaseId: '',
    error: '',
    traceId: '',
    savingReview: false,
    judging: false,
    judgingCaseId: '',
    stopJudgeRequested: false,
    loadingReport: false,
    reportLoaded: false,
    hasSavedReport: false,
    reportView: false,
    reportError: '',
    ownerId: null,
    epoch: 0,
  }),
  getters: {
    selectedCase: (s) => s.run?.cases?.find((row) => row.caseId === s.selectedCaseId) || null,
    hasPending: (s) => s.run?.cases?.some((row) => row.status === 'pending') || false,
  },
  actions: {
    resetForUser(userId) {
      if (this.ownerId === userId) return
      this.epoch += 1
      this.ownerId = userId
      this.run = null
      this.running = false
      this.stopRequested = true
      this.selectedCaseId = ''
      this.inFlightCaseId = ''
      this.error = ''
      this.traceId = ''
      this.savingReview = false
      this.judging = false
      this.judgingCaseId = ''
      this.stopJudgeRequested = true
      this.loadingReport = false
      this.reportLoaded = false
      this.hasSavedReport = false
      this.reportView = false
      this.reportError = ''
    },
    applySnapshot(snapshot) {
      this.run = snapshot
      if (!snapshot.cases.some((row) => row.caseId === this.selectedCaseId)) {
        this.selectedCaseId = snapshot.cases[0]?.caseId || ''
      }
    },
    stop() {
      this.stopRequested = true
      this.stopJudgeRequested = true
    },
    async loadLatest({ onlyIfEmpty = false } = {}) {
      if (this.running || this.judging || this.savingReview || this.loadingReport
        || (onlyIfEmpty && this.run)) return
      const epoch = ++this.epoch
      this.loadingReport = true
      this.reportError = ''
      try {
        const snapshot = await request(`${ROOT}/latest`, { silent: true })
        if (epoch !== this.epoch) return
        this.reportLoaded = true
        this.hasSavedReport = Boolean(snapshot)
        if (snapshot) {
          this.selectedCaseId = ''
          this.applySnapshot(snapshot)
          this.reportView = true
          this.error = ''
          this.traceId = ''
          this.selectedCaseId = snapshot.cases.find((row) => row.modelReview?.needsHumanReview)?.caseId
            || snapshot.cases[0]?.caseId || ''
        }
      } catch (error) {
        if (epoch === this.epoch) this.reportError = error.message || '最近报告读取失败'
      } finally {
        if (epoch === this.epoch) this.loadingReport = false
      }
    },
    async start(dataset, maxCases = null) {
      if (this.running || this.savingReview || this.judging || this.loadingReport) return
      this.running = true
      this.stopRequested = false
      this.error = ''
      this.traceId = ''
      const epoch = ++this.epoch
      try {
        const snapshot = await request(`${ROOT}/start`, {
          method: 'POST', body: { dataset, maxCases }, silent: true, timeout: 30000,
        })
        if (epoch !== this.epoch) return
        this.selectedCaseId = ''
        this.applySnapshot(snapshot)
        this.reportView = false
        await this.advance(epoch)
      } catch (error) {
        await this.recover(error, epoch)
      } finally {
        if (epoch === this.epoch) this.running = false
      }
    },
    async advance(epoch) {
      while (epoch === this.epoch && !this.stopRequested && this.hasPending) {
        this.inFlightCaseId = this.run.cases.find((row) => row.status === 'pending').caseId
        const snapshot = await request(`${ROOT}/step`, {
          method: 'POST', body: { runId: this.run.runId }, silent: true, timeout: 180000,
        })
        if (epoch !== this.epoch) return
        this.applySnapshot(snapshot)
      }
      this.inFlightCaseId = ''
    },
    async resume() {
      if (this.running || this.savingReview || this.judging || this.loadingReport || !this.run || !this.hasPending) return
      if (this.run.busy) {
        this.error = '服务端当前题仍在执行，请稍后重新读取本轮结果'
        return
      }
      this.running = true
      this.stopRequested = false
      this.error = ''
      this.traceId = ''
      const epoch = ++this.epoch
      try {
        await this.advance(epoch)
      } catch (error) {
        await this.recover(error, epoch)
      } finally {
        if (epoch === this.epoch) {
          this.running = false
          this.inFlightCaseId = ''
        }
      }
    },
    setError(error) {
      this.error = error.message || '读取失败，请重新读取本轮结果'
      this.traceId = error.traceId || ''
      this.inFlightCaseId = ''
    },
    async recover(error, epoch) {
      if (epoch !== this.epoch) return
      this.setError(error)
      if (!this.run) return
      try {
        const snapshot = await request(`${ROOT}/runs/${this.run.runId}`, { silent: true })
        if (epoch === this.epoch) this.applySnapshot(snapshot)
      } catch {
        // 保留原始执行错误与已收到的答案，读取失败不覆盖根因。
      }
    },
    async refresh() {
      if (!this.run || this.running || this.savingReview || this.judging || this.loadingReport) return
      const epoch = this.epoch
      try {
        const snapshot = await request(`${ROOT}/runs/${this.run.runId}`, { silent: true })
        if (epoch !== this.epoch) return
        this.applySnapshot(snapshot)
        this.error = ''
        this.traceId = ''
      } catch (error) {
        if (epoch === this.epoch) this.setError(error)
      }
    },
    async judge(caseId = null) {
      if (this.running || this.savingReview || this.judging || this.loadingReport || !this.run) return
      if (this.run.busy) {
        this.error = '服务端当前题仍在执行，请稍后重新读取本轮结果'
        return
      }
      const candidates = this.run.cases.filter((row) => row.status === 'completed'
        && (caseId ? row.caseId === caseId : !row.modelReview)).map((row) => row.caseId)
      if (!candidates.length) return
      const epoch = ++this.epoch
      this.judging = true
      this.stopJudgeRequested = false
      this.error = ''
      this.traceId = ''
      try {
        for (const id of candidates) {
          if (epoch !== this.epoch || this.stopJudgeRequested) break
          const row = this.run.cases.find((item) => item.caseId === id)
          this.judgingCaseId = id
          const snapshot = await request(`${ROOT}/judge`, {
            method: 'POST', body: { runId: this.run.runId, caseId: id, answerHash: row.answerHash },
            timeout: 180000, silent: true,
          })
          if (epoch !== this.epoch) return
          this.applySnapshot(snapshot)
        }
      } catch (error) {
        await this.recover(error, epoch)
      } finally {
        if (epoch === this.epoch) {
          this.judging = false
          this.judgingCaseId = ''
        }
      }
    },
    async saveReview(row, field, value, note) {
      if (this.savingReview || this.running || this.judging || this.loadingReport || !this.run || row?.status !== 'completed') return
      this.savingReview = true
      this.error = ''
      this.traceId = ''
      const epoch = ++this.epoch
      const body = {
        runId: this.run.runId, caseId: row.caseId, answerHash: row.answerHash,
        correct: row.review.correct, citationsSupported: row.review.citationsSupported,
        note: note ?? row.review.note,
      }
      if (field) body[field] = value
      try {
        const snapshot = await request(`${ROOT}/review`, { method: 'PUT', body, silent: true })
        if (epoch === this.epoch) this.applySnapshot(snapshot)
      } catch (error) {
        if (epoch === this.epoch) this.setError(error)
      } finally {
        if (epoch === this.epoch) this.savingReview = false
      }
    },
  },
})
