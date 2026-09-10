class ReplayFailure(Exception):
    def __init__(self, step_id: str, message: str):
        super().__init__(message)
        self.step_id = step_id
        self.message = message
